/*
 * Copyright 2000-2009 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package git4idea.checkin;

import com.intellij.CommonBundle;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.ThrowableComputable;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.*;
import com.intellij.openapi.vcs.changes.*;
import com.intellij.openapi.vcs.changes.ui.SelectFilePathsDialog;
import com.intellij.openapi.vcs.checkin.CheckinChangeListSpecificComponent;
import com.intellij.openapi.vcs.checkin.CheckinEnvironment;
import com.intellij.openapi.vcs.ui.RefreshableOnComponent;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.GuiUtils;
import com.intellij.ui.NonFocusableCheckBox;
import com.intellij.util.*;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.Convertor;
import com.intellij.util.ui.UIUtil;
import com.intellij.vcsUtil.VcsFileUtil;
import com.intellij.vcsUtil.VcsUtil;
import git4idea.GitPlatformFacade;
import git4idea.GitUtil;
import git4idea.commands.GitCommand;
import git4idea.commands.GitSimpleHandler;
import git4idea.config.GitConfigUtil;
import git4idea.config.GitVcsSettings;
import git4idea.history.NewGitUsersComponent;
import git4idea.history.browser.GitHeavyCommit;
import git4idea.i18n.GitBundle;
import git4idea.push.GitPusher;
import git4idea.repo.GitRepositoryFiles;
import git4idea.repo.GitRepositoryManager;
import git4idea.util.GitFileUtils;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

/**
 * Git environment for commit operations.
 */
public class GitCheckinEnvironment implements CheckinEnvironment {
  private static final Logger log = Logger.getInstance(GitCheckinEnvironment.class.getName());
  @NonNls private static final String GIT_COMMIT_MSG_FILE_PREFIX = "git-commit-msg-"; // the file name prefix for commit message file
  @NonNls private static final String GIT_COMMIT_MSG_FILE_EXT = ".txt"; // the file extension for commit message file

  private final Project myProject;
  public static final SimpleDateFormat COMMIT_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
  private final VcsDirtyScopeManager myDirtyScopeManager;
  private final GitVcsSettings mySettings;

  private String myNextCommitAuthor = null; // The author for the next commit
  private boolean myNextCommitAmend; // If true, the next commit is amended
  private Boolean myNextCommitIsPushed = null; // The push option of the next commit
  private Date myNextCommitAuthorDate;

  public GitCheckinEnvironment(@NotNull Project project, @NotNull final VcsDirtyScopeManager dirtyScopeManager, final GitVcsSettings settings) {
    myProject = project;
    myDirtyScopeManager = dirtyScopeManager;
    mySettings = settings;
  }

  public boolean keepChangeListAfterCommit(ChangeList changeList) {
    return false;
  }

  @Override
  public boolean isRefreshAfterCommitNeeded() {
    return false;
  }

  @Nullable
  public RefreshableOnComponent createAdditionalOptionsPanel(CheckinProjectPanel panel,
                                                             PairConsumer<Object, Object> additionalDataConsumer) {
    return new GitCheckinOptions(myProject, panel);
  }

  @Nullable
  public String getDefaultMessageFor(FilePath[] filesToCheckin) {
    StringBuilder rc = new StringBuilder();
    for (VirtualFile root : GitUtil.gitRoots(Arrays.asList(filesToCheckin))) {
      VirtualFile mergeMsg = root.findFileByRelativePath(GitRepositoryFiles.GIT_MERGE_MSG);
      VirtualFile squashMsg = root.findFileByRelativePath(GitRepositoryFiles.GIT_SQUASH_MSG);
      VirtualFile normalMsg = root.findFileByRelativePath(GitRepositoryFiles.GIT_COMMIT_EDITMSG);
      try {
        if (mergeMsg == null && squashMsg == null && normalMsg == null) {
          continue;
        }

        String encoding = GitConfigUtil.getCommitEncoding(myProject, root);

        if (mergeMsg != null) {
          rc.append(loadMessage(mergeMsg, encoding));
        }
        else if (squashMsg != null) {
          rc.append(loadMessage(squashMsg, encoding));
        }
        else {
          rc.append(loadMessage(normalMsg, encoding));
        }
      }
      catch (IOException e) {
        if (log.isDebugEnabled()) {
          log.debug("Unable to load merge message", e);
        }
      }
    }
    if (rc.length() != 0) {
      return rc.toString();
    }
    return null;
  }

  private static char[] loadMessage(@NotNull VirtualFile messageFile, @NotNull String encoding) throws IOException {
    return FileUtil.loadFileText(new File(messageFile.getPath()), encoding);
  }

  public String getHelpId() {
    return null;
  }

  public String getCheckinOperationName() {
    return GitBundle.getString("commit.action.name");
  }

  public List<VcsException> commit(@NotNull List<Change> changes,
                                   @NotNull String message,
                                   @NotNull NullableFunction<Object, Object> parametersHolder, Set<String> feedback) {
    List<VcsException> exceptions = new ArrayList<VcsException>();
    Map<VirtualFile, Collection<Change>> sortedChanges = sortChangesByGitRoot(changes, exceptions);
    log.assertTrue(!sortedChanges.isEmpty(), "Trying to commit an empty list of changes: " + changes);
    for (Map.Entry<VirtualFile, Collection<Change>> entry : sortedChanges.entrySet()) {
      final VirtualFile root = entry.getKey();
      try {
        File messageFile = createMessageFile(root, message);
        try {
          final Set<FilePath> added = new HashSet<FilePath>();
          final Set<FilePath> removed = new HashSet<FilePath>();
          for (Change change : entry.getValue()) {
            switch (change.getType()) {
              case NEW:
              case MODIFICATION:
                added.add(change.getAfterRevision().getFile());
                break;
              case DELETED:
                removed.add(change.getBeforeRevision().getFile());
                break;
              case MOVED:
                FilePath afterPath = change.getAfterRevision().getFile();
                FilePath beforePath = change.getBeforeRevision().getFile();
                added.add(afterPath);
                if (!GitFileUtils.shouldIgnoreCaseChange(afterPath.getPath(), beforePath.getPath())) {
                  removed.add(beforePath);
                }
                break;
              default:
                throw new IllegalStateException("Unknown change type: " + change.getType());
            }
          }
          try {
            try {
              Set<FilePath> files = new HashSet<FilePath>();
              files.addAll(added);
              files.addAll(removed);
              commit(myProject, root, files, messageFile, myNextCommitAuthor, myNextCommitAmend, myNextCommitAuthorDate);
            }
            catch (VcsException ex) {
              PartialOperation partialOperation = isMergeCommit(ex);
              if (partialOperation == PartialOperation.NONE) {
                throw ex;
              }
              if (!mergeCommit(myProject, root, added, removed, messageFile, myNextCommitAuthor, exceptions, partialOperation)) {
                throw ex;
              }
            }
          }
          finally {
            if (!messageFile.delete()) {
              log.warn("Failed to remove temporary file: " + messageFile);
            }
          }
        }
        catch (VcsException e) {
          exceptions.add(e);
        }
      }
      catch (IOException ex) {
        //noinspection ThrowableInstanceNeverThrown
        exceptions.add(new VcsException("Creation of commit message file failed", ex));
      }
    }
    if (myNextCommitIsPushed != null && myNextCommitIsPushed.booleanValue() && exceptions.isEmpty()) {
      // push
      UIUtil.invokeLaterIfNeeded(new Runnable() {
        public void run() {
          GitPusher.showPushDialogAndPerformPush(myProject, ServiceManager.getService(myProject, GitPlatformFacade.class));
        }
      });
    }
    return exceptions;
  }

  public List<VcsException> commit(List<Change> changes, String preparedComment) {
    return commit(changes, preparedComment, FunctionUtil.<Object, Object>nullConstant(), null);
  }

  /**
   * Preform a merge commit
   *
   *
   * @param project     a project
   * @param root        a vcs root
   * @param added       added files
   * @param removed     removed files
   * @param messageFile a message file for commit
   * @param author      an author
   * @param exceptions  the list of exceptions to report
   * @param partialOperation
   * @return true if merge commit was successful
   */
  private static boolean mergeCommit(final Project project,
                                     final VirtualFile root,
                                     final Set<FilePath> added,
                                     final Set<FilePath> removed,
                                     final File messageFile,
                                     final String author,
                                     List<VcsException> exceptions, @NotNull final PartialOperation partialOperation) {
    HashSet<FilePath> realAdded = new HashSet<FilePath>();
    HashSet<FilePath> realRemoved = new HashSet<FilePath>();
    // perform diff
    GitSimpleHandler diff = new GitSimpleHandler(project, root, GitCommand.DIFF);
    diff.setSilent(true);
    diff.setStdoutSuppressed(true);
    diff.addParameters("--diff-filter=ADMRUX", "--name-status", "HEAD");
    diff.endOptions();
    String output;
    try {
      output = diff.run();
    }
    catch (VcsException ex) {
      exceptions.add(ex);
      return false;
    }
    String rootPath = root.getPath();
    for (StringTokenizer lines = new StringTokenizer(output, "\n", false); lines.hasMoreTokens();) {
      String line = lines.nextToken().trim();
      if (line.length() == 0) {
        continue;
      }
      String[] tk = line.split("\t");
      switch (tk[0].charAt(0)) {
        case 'M':
        case 'A':
          realAdded.add(VcsUtil.getFilePath(rootPath + "/" + tk[1]));
          break;
        case 'D':
          realRemoved.add(VcsUtil.getFilePathForDeletedFile(rootPath + "/" + tk[1], false));
          break;
        default:
          throw new IllegalStateException("Unexpected status: " + line);
      }
    }
    realAdded.removeAll(added);
    realRemoved.removeAll(removed);
    if (realAdded.size() != 0 || realRemoved.size() != 0) {

      final List<FilePath> files = new ArrayList<FilePath>();
      files.addAll(realAdded);
      files.addAll(realRemoved);
      final Ref<Boolean> mergeAll = new Ref<Boolean>();
      try {
        GuiUtils.runOrInvokeAndWait(new Runnable() {
          public void run() {
            String message = GitBundle.message("commit.partial.merge.message", partialOperation.getName());
            SelectFilePathsDialog dialog = new SelectFilePathsDialog(project, files, message,
                                                                     null, "Commit All Files", CommonBundle.getCancelButtonText(), false);
            dialog.setTitle(GitBundle.getString("commit.partial.merge.title"));
            dialog.show();
            mergeAll.set(dialog.isOK());
          }
        });
      }
      catch (RuntimeException ex) {
        throw ex;
      }
      catch (Exception ex) {
        throw new RuntimeException("Unable to invoke a message box on AWT thread", ex);
      }
      if (!mergeAll.get()) {
        return false;
      }
      // update non-indexed files
      if (!updateIndex(project, root, realAdded, realRemoved, exceptions)) {
        return false;
      }
      for (FilePath f : realAdded) {
        VcsDirtyScopeManager.getInstance(project).fileDirty(f);
      }
      for (FilePath f : realRemoved) {
        VcsDirtyScopeManager.getInstance(project).fileDirty(f);
      }
    }
    // perform merge commit
    try {
      GitSimpleHandler handler = new GitSimpleHandler(project, root, GitCommand.COMMIT);
      handler.addParameters("-F", messageFile.getAbsolutePath());
      if (author != null) {
        handler.addParameters("--author=" + author);
      }
      handler.endOptions();
      handler.run();
      GitRepositoryManager manager = GitUtil.getRepositoryManager(project);
      manager.updateRepository(root);
    }
    catch (VcsException ex) {
      exceptions.add(ex);
      return false;
    }
    return true;
  }

  /**
   * Check if commit has failed due to unfinished merge or cherry-pick.
   *
   *
   * @param ex an exception to examine
   * @return true if exception means that there is a partial commit during merge
   */
  private static PartialOperation isMergeCommit(final VcsException ex) {
    String message = ex.getMessage();
    if (message.contains("fatal: cannot do a partial commit during a merge")) {
      return PartialOperation.MERGE;
    }
    if (message.contains("fatal: cannot do a partial commit during a cherry-pick")) {
      return PartialOperation.CHERRY_PICK;
    }
    return PartialOperation.NONE;
  }

  /**
   * Update index (delete and remove files)
   *
   * @param project    the project
   * @param root       a vcs root
   * @param added      added/modified files to commit
   * @param removed    removed files to commit
   * @param exceptions a list of exceptions to update
   * @return true if index was updated successfully
   */
  private static boolean updateIndex(final Project project,
                                     final VirtualFile root,
                                     final Collection<FilePath> added,
                                     final Collection<FilePath> removed,
                                     final List<VcsException> exceptions) {
    boolean rc = true;
    if (!added.isEmpty()) {
      try {
        GitFileUtils.addPaths(project, root, added);
      }
      catch (VcsException ex) {
        exceptions.add(ex);
        rc = false;
      }
    }
    if (!removed.isEmpty()) {
      try {
        GitFileUtils.delete(project, root, removed, "--ignore-unmatch");
      }
      catch (VcsException ex) {
        exceptions.add(ex);
        rc = false;
      }
    }
    return rc;
  }

  /**
   * Create a file that contains the specified message
   *
   * @param root    a git repository root
   * @param message a message to write
   * @return a file reference
   * @throws IOException if file cannot be created
   */
  private File createMessageFile(VirtualFile root, final String message) throws IOException {
    // filter comment lines
    File file = FileUtil.createTempFile(GIT_COMMIT_MSG_FILE_PREFIX, GIT_COMMIT_MSG_FILE_EXT);
    file.deleteOnExit();
    @NonNls String encoding = GitConfigUtil.getCommitEncoding(myProject, root);
    Writer out = new OutputStreamWriter(new FileOutputStream(file), encoding);
    try {
      out.write(message);
    }
    finally {
      out.close();
    }
    return file;
  }

  /**
   * {@inheritDoc}
   */
  public List<VcsException> scheduleMissingFileForDeletion(List<FilePath> files) {
    ArrayList<VcsException> rc = new ArrayList<VcsException>();
    Map<VirtualFile, List<FilePath>> sortedFiles;
    try {
      sortedFiles = GitUtil.sortFilePathsByGitRoot(files);
    }
    catch (VcsException e) {
      rc.add(e);
      return rc;
    }
    for (Map.Entry<VirtualFile, List<FilePath>> e : sortedFiles.entrySet()) {
      try {
        final VirtualFile root = e.getKey();
        GitFileUtils.delete(myProject, root, e.getValue());
        markRootDirty(root);
      }
      catch (VcsException ex) {
        rc.add(ex);
      }
    }
    return rc;
  }

  /**
   * Prepare delete files handler.
   *
   *
   *
   * @param project              the project
   * @param root                 a vcs root
   * @param files                a files to commit
   * @param message              a message file to use
   * @param nextCommitAuthor     a author for the next commit
   * @param nextCommitAmend      true, if the commit should be amended
   * @param nextCommitAuthorDate Author date timestamp to override the date of the commit or null if this overriding is not needed.
   * @return a simple handler that does the task
   * @throws VcsException in case of git problem
   */
  private static void commit(Project project,
                             VirtualFile root,
                             Collection<FilePath> files,
                             File message,
                             final String nextCommitAuthor,
                             boolean nextCommitAmend, Date nextCommitAuthorDate)
    throws VcsException {
    boolean amend = nextCommitAmend;
    for (List<String> paths : VcsFileUtil.chunkPaths(root, files)) {
      GitSimpleHandler handler = new GitSimpleHandler(project, root, GitCommand.COMMIT);
      if (amend) {
        handler.addParameters("--amend");
      }
      else {
        amend = true;
      }
      handler.addParameters("--only", "-F", message.getAbsolutePath());
      if (nextCommitAuthor != null) {
        handler.addParameters("--author=" + nextCommitAuthor);
      }
      if (nextCommitAuthorDate != null) {
        handler.addParameters("--date", COMMIT_DATE_FORMAT.format(nextCommitAuthorDate));
      }
      handler.endOptions();
      handler.addParameters(paths);
      handler.run();
    }
    if (!project.isDisposed()) {
      GitRepositoryManager manager = GitUtil.getRepositoryManager(project);
      manager.updateRepository(root);
    }
  }


  /**
   * {@inheritDoc}
   */
  public List<VcsException> scheduleUnversionedFilesForAddition(List<VirtualFile> files) {
    ArrayList<VcsException> rc = new ArrayList<VcsException>();
    Map<VirtualFile, List<VirtualFile>> sortedFiles;
    try {
      sortedFiles = GitUtil.sortFilesByGitRoot(files);
    }
    catch (VcsException e) {
      rc.add(e);
      return rc;
    }
    for (Map.Entry<VirtualFile, List<VirtualFile>> e : sortedFiles.entrySet()) {
      try {
        final VirtualFile root = e.getKey();
        GitFileUtils.addFiles(myProject, root, e.getValue());
        markRootDirty(root);
      }
      catch (VcsException ex) {
        rc.add(ex);
      }
    }
    return rc;
  }

  private enum PartialOperation {
    NONE("none"),
    MERGE("merge"),
    CHERRY_PICK("cherry-pick");

    private final String myName;

    PartialOperation(String name) {
      myName = name;
    }

    String getName() {
      return myName;
    }
  }

  /**
   * Sort changes by roots
   *
   * @param changes    a change list
   * @param exceptions exceptions to collect
   * @return sorted changes
   */
  private static Map<VirtualFile, Collection<Change>> sortChangesByGitRoot(@NotNull List<Change> changes, List<VcsException> exceptions) {
    Map<VirtualFile, Collection<Change>> result = new HashMap<VirtualFile, Collection<Change>>();
    for (Change change : changes) {
      final ContentRevision afterRevision = change.getAfterRevision();
      final ContentRevision beforeRevision = change.getBeforeRevision();
      // nothing-to-nothing change cannot happen.
      assert beforeRevision != null || afterRevision != null;
      // note that any path will work, because changes could happen within single vcs root
      final FilePath filePath = afterRevision != null ? afterRevision.getFile() : beforeRevision.getFile();
      final VirtualFile vcsRoot;
      try {
        // the parent paths for calculating roots in order to account for submodules that contribute
        // to the parent change. The path "." is never is valid change, so there should be no problem
        // with it.
        vcsRoot = GitUtil.getGitRoot(filePath.getParentPath());
      }
      catch (VcsException e) {
        exceptions.add(e);
        continue;
      }
      Collection<Change> changeList = result.get(vcsRoot);
      if (changeList == null) {
        changeList = new ArrayList<Change>();
        result.put(vcsRoot, changeList);
      }
      changeList.add(change);
    }
    return result;
  }

  /**
   * Mark root as dirty
   *
   * @param root a vcs root to rescan
   */
  private void markRootDirty(final VirtualFile root) {
    // Note that the root is invalidated because changes are detected per-root anyway.
    // Otherwise it is not possible to detect moves.
    myDirtyScopeManager.dirDirtyRecursively(root);
  }

  public void reset() {
    myNextCommitAmend = false;
    myNextCommitAuthor = null;
    myNextCommitIsPushed = null;
    myNextCommitAuthorDate = null;
  }

  /**
   * Checkin options for git
   */
  private class GitCheckinOptions implements CheckinChangeListSpecificComponent {
    /**
     * A container panel
     */
    private final JPanel myPanel;
    /**
     * The author ComboBox, the combobox contains previously selected authors.
     */
    private final JComboBox myAuthor;
    /**
     * The amend checkbox
     */
    private final JCheckBox myAmend;
    private Date myAuthorDate;
    @Nullable private String myPreviousMessage;
    @Nullable private String myAmendedMessage;

    @NotNull private final CheckinProjectPanel myCheckinPanel;

    /**
     * A constructor
     *
     * @param project
     * @param panel
     */
    GitCheckinOptions(@NotNull final Project project, @NotNull CheckinProjectPanel panel) {
      myCheckinPanel = panel;
      myPanel = new JPanel(new GridBagLayout());
      final Insets insets = new Insets(2, 2, 2, 2);
      // add authors drop down
      GridBagConstraints c = new GridBagConstraints();
      c.gridx = 0;
      c.gridy = 0;
      c.anchor = GridBagConstraints.WEST;
      c.insets = insets;
      final JLabel authorLabel = new JLabel(GitBundle.message("commit.author"));
      myPanel.add(authorLabel, c);

      c = new GridBagConstraints();
      c.anchor = GridBagConstraints.CENTER;
      c.insets = insets;
      c.gridx = 1;
      c.gridy = 0;
      c.weightx = 1;
      c.fill = GridBagConstraints.HORIZONTAL;
      final List<String> usersList = getUsersList(project, myCheckinPanel.getRoots());
      final Set<String> authors = usersList == null ? new HashSet<String>() : new HashSet<String>(usersList);
      ContainerUtil.addAll(authors, mySettings.getCommitAuthors());
      List<String> list = new ArrayList<String>(authors);
      Collections.sort(list);
      list = ObjectsConvertor.convert(list, new Convertor<String, String>() {
        @Override
        public String convert(String o) {
          return StringUtil.shortenTextWithEllipsis(o, 30, 0);
        }
      });
      myAuthor = new JComboBox(ArrayUtil.toObjectArray(list));
      myAuthor.insertItemAt("", 0);
      myAuthor.setSelectedItem("");
      myAuthor.setEditable(true);
      authorLabel.setLabelFor(myAuthor);
      myAuthor.setToolTipText(GitBundle.getString("commit.author.tooltip"));
      myPanel.add(myAuthor, c);
      // add amend checkbox
      c = new GridBagConstraints();
      c.gridx = 0;
      c.gridy = 1;
      c.gridwidth = 2;
      c.anchor = GridBagConstraints.CENTER;
      c.insets = insets;
      c.weightx = 1;
      c.fill = GridBagConstraints.HORIZONTAL;
      myAmend = new NonFocusableCheckBox(GitBundle.getString("commit.amend"));
      myAmend.setMnemonic('m');
      myAmend.setSelected(false);
      myAmend.setToolTipText(GitBundle.getString("commit.amend.tooltip"));
      myPanel.add(myAmend, c);

      myPreviousMessage = myCheckinPanel.getCommitMessage();

      myAmend.addActionListener(new ActionListener() {
        @Override
        public void actionPerformed(ActionEvent e) {
          if (myAmend.isSelected()) {
            if (myPreviousMessage.equals(myCheckinPanel.getCommitMessage())) { // if user has already typed something, don't revert it
              if (myAmendedMessage == null) {
                loadMessageInModalTask(project);
              }
              else { // checkbox is selected not the first time
                substituteCommitMessage(myAmendedMessage);
              }
            }
          }
          else {
            // there was the amended message, but user has changed it => not reverting
            if (myCheckinPanel.getCommitMessage().equals(myAmendedMessage)) {
              myCheckinPanel.setCommitMessage(myPreviousMessage);
            }
          }
        }
      });
    }

    private void loadMessageInModalTask(@NotNull Project project) {
      try {
        String messageFromGit =
          ProgressManager.getInstance().runProcessWithProgressSynchronously(new ThrowableComputable<String, VcsException>() {
            @Override
            public String compute() throws VcsException {
              return getLastCommitMessage();
            }
          }, "Reading commit message...", false, project);
        if (!StringUtil.isEmptyOrSpaces(messageFromGit)) {
          substituteCommitMessage(messageFromGit);
          myAmendedMessage = messageFromGit;
        }
      }
      catch (VcsException e) {
        Messages.showErrorDialog(getComponent(), "Couldn't load commit message of the commit to amend.\n" + e.getMessage(),
                                 "Commit Message not Loaded");
        log.info(e);
      }
    }

    private void substituteCommitMessage(@NotNull String newMessage) {
      myPreviousMessage = myCheckinPanel.getCommitMessage();
      myCheckinPanel.setCommitMessage(newMessage);
    }

    @Nullable
    private String getLastCommitMessage() throws VcsException {
      Set<VirtualFile> roots = GitUtil.gitRoots(getSelectedFilePaths());
      final Ref<VcsException> exception = Ref.create();
      String joined = StringUtil.join(roots, new Function<VirtualFile, String>() {
        @Override
        public String fun(VirtualFile root) {
          try {
            return getLastCommitMessage(root);
          }
          catch (VcsException e) {
            exception.set(e);
            return null;
          }
        }
      }, "\n");
      if (!exception.isNull()) {
        throw exception.get();
      }
      return joined;
    }

    @Nullable
    private String getLastCommitMessage(@NotNull VirtualFile root) throws VcsException {
      GitSimpleHandler h = new GitSimpleHandler(myProject, root, GitCommand.LOG);
      h.addParameters("--max-count=1");
      // only message: subject + body; "%-b" means that preceding line-feeds will be deleted if the body is empty
      h.addParameters("--pretty=%s%n%n%-b");
      return h.run();
    }

    @NotNull
    private List<FilePath> getSelectedFilePaths() {
      return ContainerUtil.map(myCheckinPanel.getFiles(), new Function<File, FilePath>() {
        @Override
        public FilePath fun(File file) {
          return new FilePathImpl(file, file.isDirectory());
        }
      });
    }

    private List<String> getUsersList(final Project project, final Collection<VirtualFile> roots) {
      return NewGitUsersComponent.getInstance(project).get();
    }

    /**
     * {@inheritDoc}
     */
    public JComponent getComponent() {
      return myPanel;
    }

    /**
     * {@inheritDoc}
     */
    public void refresh() {
      myAuthor.setSelectedItem("");
      myAmend.setSelected(false);
      reset();
    }

    /**
     * {@inheritDoc}
     */
    public void saveState() {
      String author = (String)myAuthor.getEditor().getItem();
      myNextCommitAuthor = author.length() == 0 ? null : author;
      if (author.length() == 0) {
        myNextCommitAuthor = null;
      }
      else {
        myNextCommitAuthor = author;
        mySettings.saveCommitAuthor(author);
      }
      myNextCommitAmend = myAmend.isSelected();
      myNextCommitAuthorDate = myAuthorDate;
    }

    /**
     * {@inheritDoc}
     */
    public void restoreState() {
      refresh();
    }

    @Override
    public void onChangeListSelected(LocalChangeList list) {
      Object data = list.getData();
      if (data instanceof GitHeavyCommit) {
        GitHeavyCommit commit = (GitHeavyCommit)data;
        String author = String.format("%s <%s>", commit.getAuthor(), commit.getAuthorEmail());
        myAuthor.getEditor().setItem(author);
        myAuthorDate = new Date(commit.getAuthorTime());
      }
    }
  }

  public void setNextCommitIsPushed(Boolean nextCommitIsPushed) {
    myNextCommitIsPushed = nextCommitIsPushed;
  }
}
