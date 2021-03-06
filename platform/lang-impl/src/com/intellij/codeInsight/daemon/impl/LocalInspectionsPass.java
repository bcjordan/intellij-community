/*
 * Copyright 2000-2013 JetBrains s.r.o.
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

package com.intellij.codeInsight.daemon.impl;

import com.intellij.codeHighlighting.Pass;
import com.intellij.codeInsight.daemon.DaemonBundle;
import com.intellij.codeInsight.daemon.HighlightDisplayKey;
import com.intellij.codeInsight.daemon.impl.analysis.HighlightingLevelManager;
import com.intellij.codeInsight.daemon.impl.quickfix.QuickFixAction;
import com.intellij.codeInsight.intention.EmptyIntentionAction;
import com.intellij.codeInspection.*;
import com.intellij.codeInspection.ex.*;
import com.intellij.codeInspection.ui.InspectionToolPresentation;
import com.intellij.concurrency.JobLauncher;
import com.intellij.injected.editor.DocumentWindow;
import com.intellij.lang.Language;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.keymap.Keymap;
import com.intellij.openapi.keymap.KeymapManager;
import com.intellij.openapi.keymap.KeymapUtil;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.util.*;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.profile.codeInspection.InspectionProjectProfileManager;
import com.intellij.profile.codeInspection.InspectionProjectProfileManagerImpl;
import com.intellij.profile.codeInspection.SeverityProvider;
import com.intellij.psi.*;
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageUtil;
import com.intellij.util.ConcurrencyUtil;
import com.intellij.util.Function;
import com.intellij.util.Processor;
import com.intellij.util.containers.ConcurrentHashMap;
import com.intellij.util.containers.MultiMap;
import com.intellij.util.containers.TransferToEDTQueue;
import com.intellij.util.ui.UIUtil;
import com.intellij.xml.util.XmlStringUtil;
import gnu.trove.THashMap;
import gnu.trove.THashSet;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentMap;

/**
 * @author max
 */
public class LocalInspectionsPass extends ProgressableTextEditorHighlightingPass implements DumbAware {
  private static final Logger LOG = Logger.getInstance("#com.intellij.codeInsight.daemon.impl.LocalInspectionsPass");
  public static final TextRange EMPTY_PRIORITY_RANGE = TextRange.EMPTY_RANGE;
  private static final Condition<PsiFile> FILE_FILTER = new Condition<PsiFile>() {
    @Override
    public boolean value(PsiFile file) {
      return HighlightingLevelManager.getInstance(file.getProject()).shouldInspect(file);
    }
  };
  private final int myStartOffset;
  private final int myEndOffset;
  private final TextRange myPriorityRange;
  private final boolean myIgnoreSuppressed;
  private final ConcurrentMap<PsiFile, List<InspectionResult>> result = new ConcurrentHashMap<PsiFile, List<InspectionResult>>();
  private static final String PRESENTABLE_NAME = DaemonBundle.message("pass.inspection");
  private volatile List<HighlightInfo> myInfos = Collections.emptyList();
  private final String myShortcutText;
  private final SeverityRegistrar mySeverityRegistrar;
  private final InspectionProfileWrapper myProfileWrapper;
  private boolean myFailFastOnAcquireReadAction;

  public LocalInspectionsPass(@NotNull PsiFile file,
                              @Nullable Document document,
                              int startOffset,
                              int endOffset,
                              @NotNull TextRange priorityRange,
                              boolean ignoreSuppressed,
                              @NotNull HighlightInfoProcessor highlightInfoProcessor) {
    super(file.getProject(), document, PRESENTABLE_NAME, file, null, new TextRange(startOffset, endOffset), true, highlightInfoProcessor);
    myStartOffset = startOffset;
    myEndOffset = endOffset;
    myPriorityRange = priorityRange;
    myIgnoreSuppressed = ignoreSuppressed;
    setId(Pass.LOCAL_INSPECTIONS);

    final KeymapManager keymapManager = KeymapManager.getInstance();
    if (keymapManager != null) {
      final Keymap keymap = keymapManager.getActiveKeymap();
      myShortcutText = keymap == null ? "" : "(" + KeymapUtil.getShortcutsText(keymap.getShortcuts(IdeActions.ACTION_SHOW_ERROR_DESCRIPTION)) + ")";
    }
    else {
      myShortcutText = "";
    }
    InspectionProfileWrapper profileToUse = InspectionProjectProfileManagerImpl.getInstanceImpl(myProject).getProfileWrapper();

    Function<InspectionProfileWrapper,InspectionProfileWrapper> custom = file.getUserData(InspectionProfileWrapper.CUSTOMIZATION_KEY);
    if (custom != null) {
      profileToUse = custom.fun(profileToUse);
    }

    myProfileWrapper = profileToUse;
    assert myProfileWrapper != null;
    mySeverityRegistrar = ((SeverityProvider)myProfileWrapper.getInspectionProfile().getProfileManager()).getSeverityRegistrar();

    // initial guess
    setProgressLimit(300 * 2);
  }

  @Override
  protected void collectInformationWithProgress(@NotNull ProgressIndicator progress) {
    try {
      if (!HighlightingLevelManager.getInstance(myProject).shouldInspect(myFile)) return;
      final InspectionManagerEx iManager = (InspectionManagerEx)InspectionManager.getInstance(myProject);
      final InspectionProfileWrapper profile = myProfileWrapper;
      inspect(getInspectionTools(profile), iManager, true, true, DumbService.isDumb(myProject), progress);
    }
    finally {
      disposeDescriptors();
    }
  }

  private void disposeDescriptors() {
    result.clear();
  }

  public void doInspectInBatch(@NotNull final GlobalInspectionContextImpl context,
                               @NotNull final InspectionManagerEx iManager,
                               @NotNull final List<LocalInspectionToolWrapper> toolWrappers) {
    final ProgressIndicator progress = ProgressManager.getInstance().getProgressIndicator();
    inspect(new ArrayList<LocalInspectionToolWrapper>(toolWrappers), iManager, false, false, false, progress);
    addDescriptorsFromInjectedResults(iManager, context);
    List<InspectionResult> resultList = result.get(myFile);
    if (resultList == null) return;
    for (InspectionResult inspectionResult : resultList) {
      LocalInspectionToolWrapper toolWrapper = inspectionResult.tool;
      for (ProblemDescriptor descriptor : inspectionResult.foundProblems) {
        addDescriptors(toolWrapper, descriptor, context);
      }
    }
  }

  private void addDescriptors(@NotNull LocalInspectionToolWrapper toolWrapper,
                              @NotNull ProblemDescriptor descriptor,
                              @NotNull GlobalInspectionContextImpl context) {
    InspectionToolPresentation toolPresentation = context.getPresentation(toolWrapper);
    LocalDescriptorsUtil.addProblemDescriptors(Collections.singletonList(descriptor), toolPresentation, myIgnoreSuppressed,
                                               context,
                                               toolWrapper.getTool());
  }

  private void addDescriptorsFromInjectedResults(@NotNull InspectionManagerEx iManager, @NotNull GlobalInspectionContextImpl context) {
    InjectedLanguageManager ilManager = InjectedLanguageManager.getInstance(myProject);
    PsiDocumentManager documentManager = PsiDocumentManager.getInstance(myProject);

    for (Map.Entry<PsiFile, List<InspectionResult>> entry : result.entrySet()) {
      PsiFile file = entry.getKey();
      if (file == myFile) continue; // not injected
      DocumentWindow documentRange = (DocumentWindow)documentManager.getDocument(file);
      List<InspectionResult> resultList = entry.getValue();
      for (InspectionResult inspectionResult : resultList) {
        LocalInspectionToolWrapper toolWrapper = inspectionResult.tool;
        for (ProblemDescriptor descriptor : inspectionResult.foundProblems) {

          PsiElement psiElement = descriptor.getPsiElement();
          if (psiElement == null) continue;
          if (SuppressionUtil.inspectionResultSuppressed(psiElement, toolWrapper.getTool())) continue;
          List<TextRange> editables = ilManager.intersectWithAllEditableFragments(file, ((ProblemDescriptorBase)descriptor).getTextRange());
          for (TextRange editable : editables) {
            TextRange hostRange = documentRange.injectedToHost(editable);
            QuickFix[] fixes = descriptor.getFixes();
            LocalQuickFix[] localFixes = null;
            if (fixes != null) {
              localFixes = new LocalQuickFix[fixes.length];
              for (int k = 0; k < fixes.length; k++) {
                QuickFix fix = fixes[k];
                localFixes[k] = (LocalQuickFix)fix;
              }
            }
            ProblemDescriptor patchedDescriptor = iManager.createProblemDescriptor(myFile, hostRange, descriptor.getDescriptionTemplate(),
                                                                                   descriptor.getHighlightType(), true, localFixes);
            addDescriptors(toolWrapper, patchedDescriptor, context);
          }
        }
      }
    }
  }

  private void inspect(@NotNull final List<LocalInspectionToolWrapper> toolWrappers,
                       @NotNull final InspectionManagerEx iManager,
                       final boolean isOnTheFly,
                       boolean failFastOnAcquireReadAction,
                       boolean checkDumbAwareness,
                       @NotNull final ProgressIndicator progress) {
    myFailFastOnAcquireReadAction = failFastOnAcquireReadAction;
    if (toolWrappers.isEmpty()) return;

    List<PsiElement> inside = new ArrayList<PsiElement>();
    List<PsiElement> outside = new ArrayList<PsiElement>();
    Divider.divideInsideAndOutside(myFile, myStartOffset, myEndOffset, myPriorityRange, inside, new ArrayList<ProperTextRange>(), outside, new ArrayList<ProperTextRange>(),
                                   true, FILE_FILTER);

    MultiMap<LocalInspectionToolWrapper, String> toolToLanguages = getToolsForElements(toolWrappers, checkDumbAwareness, inside, outside);

    setProgressLimit(toolToLanguages.size() * 2L);
    final LocalInspectionToolSession session = new LocalInspectionToolSession(myFile, myStartOffset, myEndOffset);

    List<InspectionContext> init =
      visitPriorityElementsAndInit(toolToLanguages, iManager, isOnTheFly, progress, inside, session, toolWrappers, checkDumbAwareness);
    visitRestElementsAndCleanup(progress, outside, session, init);
    inspectInjectedPsi(outside, isOnTheFly, progress, iManager, false, checkDumbAwareness, toolWrappers);

    progress.checkCanceled();

    myInfos = new ArrayList<HighlightInfo>();
    addHighlightsFromResults(myInfos, progress);
  }

  @NotNull
  private static MultiMap<LocalInspectionToolWrapper, String> getToolsForElements(@NotNull List<LocalInspectionToolWrapper> toolWrappers,
                                                                                  boolean checkDumbAwareness,
                                                                                  @NotNull List<PsiElement> inside,
                                                                                  @NotNull List<PsiElement> outside) {
    Set<Language> languages = new SmartHashSet<Language>();
    Map<String, Language> langIds = new SmartHashMap<String, Language>();
    Set<String> dialects = new SmartHashSet<String>();
    for (PsiElement element : inside) {
      Language language = element.getLanguage();
      if (languages.add(language)) {
        langIds.put(language.getID(), language);
        for (Language dialect : language.getDialects()) {
          dialects.add(dialect.getID());
        }
      }
    }
    for (PsiElement element : outside) {
      Language language = element.getLanguage();
      if (languages.add(language)) {
        langIds.put(language.getID(), language);
        for (Language dialect : language.getDialects()) {
          dialects.add(dialect.getID());
        }
      }
    }
    MultiMap<LocalInspectionToolWrapper, String> toolToLanguages = new MultiMap<LocalInspectionToolWrapper, String>() {
      @Override
      protected Collection<String> createCollection() {
        return new SmartHashSet<String>();
      }

      @Override
      protected Collection<String> createEmptyCollection() {
        return Collections.emptySet();
      }
    };
    for (LocalInspectionToolWrapper wrapper : toolWrappers) {
      String language = wrapper.getLanguage();
      if (language == null) {
        LocalInspectionTool tool = wrapper.getTool();
        if (!checkDumbAwareness || tool instanceof DumbAware) {
          toolToLanguages.put(wrapper, null);
        }
        continue;
      }
      Language lang = langIds.get(language);
      if (lang != null) {
        LocalInspectionTool tool = wrapper.getTool();
        if (!checkDumbAwareness || tool instanceof DumbAware) {
          toolToLanguages.putValue(wrapper, language);
          if (wrapper.applyToDialects()) {
            for (Language dialect : lang.getDialects()) {
              toolToLanguages.putValue(wrapper, dialect.getID());
            }
          }
        }
      }
      else if (wrapper.applyToDialects() && dialects.contains(language)) {
        LocalInspectionTool tool = wrapper.getTool();
        if (!checkDumbAwareness || tool instanceof DumbAware) {
          toolToLanguages.putValue(wrapper, language);
        }
      }
    }
    return toolToLanguages;
  }

  @NotNull
  private List<InspectionContext> visitPriorityElementsAndInit(@NotNull MultiMap<LocalInspectionToolWrapper, String> toolToLanguages,
                                                               @NotNull final InspectionManagerEx iManager,
                                                               final boolean isOnTheFly,
                                                               @NotNull final ProgressIndicator indicator,
                                                               @NotNull final List<PsiElement> elements,
                                                               @NotNull final LocalInspectionToolSession session,
                                                               @NotNull List<LocalInspectionToolWrapper> wrappers,
                                                               boolean checkDumbAwareness) {
    final List<InspectionContext> init = new ArrayList<InspectionContext>();
    List<Map.Entry<LocalInspectionToolWrapper, Collection<String>>> entries = new ArrayList<Map.Entry<LocalInspectionToolWrapper, Collection<String>>>(toolToLanguages.entrySet());
    Processor<Map.Entry<LocalInspectionToolWrapper, Collection<String>>> processor =
      new Processor<Map.Entry<LocalInspectionToolWrapper, Collection<String>>>() {
        @Override
        public boolean process(final Map.Entry<LocalInspectionToolWrapper, Collection<String>> pair) {
          return runToolOnElements(pair.getKey(), pair.getValue(), iManager, isOnTheFly, indicator, elements, session, init);
        }
      };
    boolean result = JobLauncher.getInstance().invokeConcurrentlyUnderProgress(entries, indicator, myFailFastOnAcquireReadAction, processor);
    if (!result) throw new ProcessCanceledException();
    inspectInjectedPsi(elements, isOnTheFly, indicator, iManager, true, checkDumbAwareness, wrappers);
    return init;
  }

  private boolean runToolOnElements(@NotNull final LocalInspectionToolWrapper toolWrapper,
                                    Collection<String> languages,
                                    @NotNull final InspectionManagerEx iManager,
                                    final boolean isOnTheFly,
                                    @NotNull final ProgressIndicator indicator,
                                    @NotNull final List<PsiElement> elements,
                                    @NotNull final LocalInspectionToolSession session,
                                    @NotNull List<InspectionContext> init) {
    indicator.checkCanceled();

    ApplicationManager.getApplication().assertReadAccessAllowed();
    LocalInspectionTool tool = toolWrapper.getTool();
    final boolean[] applyIncrementally = {isOnTheFly};
    ProblemsHolder holder = new ProblemsHolder(iManager, myFile, isOnTheFly) {
        @Override
        public void registerProblem(@NotNull ProblemDescriptor descriptor) {
          super.registerProblem(descriptor);
          if (applyIncrementally[0]) {
            addDescriptorIncrementally(descriptor, toolWrapper, indicator);
          }
        }
    };
    PsiElementVisitor visitor = InspectionEngine.createVisitorAndAcceptElements(tool, holder, isOnTheFly, session, elements, languages);

    synchronized (init) {
      init.add(new InspectionContext(toolWrapper, holder, visitor, languages));
    }
    advanceProgress(1);

    if (holder.hasResults()) {
      appendDescriptors(myFile, holder.getResults(), toolWrapper);
    }
    applyIncrementally[0] = false; // do not apply incrementally outside visible range
    return true;
  }

  private void visitRestElementsAndCleanup(@NotNull final ProgressIndicator indicator,
                                           @NotNull final List<PsiElement> elements,
                                           @NotNull final LocalInspectionToolSession session,
                                           @NotNull List<InspectionContext> init) {
    Processor<InspectionContext> processor =
      new Processor<InspectionContext>() {
        @Override
        public boolean process(InspectionContext context) {
          indicator.checkCanceled();
          ApplicationManager.getApplication().assertReadAccessAllowed();
          InspectionEngine.acceptElements(elements, context.visitor, context.languageIds);
          advanceProgress(1);
          context.tool.getTool().inspectionFinished(session, context.holder);

          if (context.holder.hasResults()) {
            appendDescriptors(myFile, context.holder.getResults(), context.tool);
          }
          return true;
        }
      };
    boolean result = JobLauncher.getInstance().invokeConcurrentlyUnderProgress(init, indicator, myFailFastOnAcquireReadAction, processor);
    if (!result) {
      throw new ProcessCanceledException();
    }
  }

  void inspectInjectedPsi(@NotNull final List<PsiElement> elements,
                          final boolean onTheFly,
                          @NotNull final ProgressIndicator indicator,
                          @NotNull final InspectionManagerEx iManager,
                          final boolean inVisibleRange,
                          final boolean checkDumbAwareness,
                          @NotNull final List<LocalInspectionToolWrapper> wrappers) {
    final Set<PsiFile> injected = new THashSet<PsiFile>();
    for (PsiElement element : elements) {
      InjectedLanguageUtil.enumerate(element, myFile, false, new PsiLanguageInjectionHost.InjectedPsiVisitor() {
        @Override
        public void visit(@NotNull PsiFile injectedPsi, @NotNull List<PsiLanguageInjectionHost.Shred> places) {
          injected.add(injectedPsi);
        }
      });
    }
    if (injected.isEmpty()) return;
    if (!JobLauncher.getInstance().invokeConcurrentlyUnderProgress(new ArrayList<PsiFile>(injected), indicator,
                                                                   myFailFastOnAcquireReadAction,
                                                                   new Processor<PsiFile>() {
                                                                     @Override
                                                                     public boolean process(final PsiFile injectedPsi) {
                                                                       doInspectInjectedPsi(injectedPsi, onTheFly, indicator, iManager,
                                                                                            inVisibleRange,
                                                                                            wrappers, checkDumbAwareness);
                                                                       return true;
                                                                     }
                                                                   })) throw new ProcessCanceledException();
  }

  @Nullable
  private HighlightInfo highlightInfoFromDescriptor(@NotNull ProblemDescriptor problemDescriptor,
                                                    @NotNull HighlightInfoType highlightInfoType,
                                                    @NotNull String message,
                                                    String toolTip,
                                                    PsiElement psiElement) {
    TextRange textRange = ((ProblemDescriptorBase)problemDescriptor).getTextRange();
    if (textRange == null || psiElement == null) return null;
    boolean isFileLevel = psiElement instanceof PsiFile && textRange.equals(psiElement.getTextRange());

    final HighlightSeverity severity = highlightInfoType.getSeverity(psiElement);
    TextAttributes attributes = mySeverityRegistrar.getTextAttributesBySeverity(severity);
    HighlightInfo.Builder b = HighlightInfo.newHighlightInfo(highlightInfoType)
                              .range(psiElement, textRange.getStartOffset(), textRange.getEndOffset())
                              .description(message)
                              .severity(severity);
    if (toolTip != null) b.escapedToolTip(toolTip);
    if (attributes != null) b.textAttributes(attributes);
    if (problemDescriptor.isAfterEndOfLine()) b.endOfLine();
    if (isFileLevel) b.fileLevelAnnotation();
    if (problemDescriptor.getProblemGroup() != null) b.problemGroup(problemDescriptor.getProblemGroup());

    return b.create();
  }

  private final Map<TextRange, RangeMarker> ranges2markersCache = new THashMap<TextRange, RangeMarker>();
  private final TransferToEDTQueue<Trinity<ProblemDescriptor, LocalInspectionToolWrapper,ProgressIndicator>> myTransferToEDTQueue
    = new TransferToEDTQueue<Trinity<ProblemDescriptor, LocalInspectionToolWrapper,ProgressIndicator>>("Apply inspection results", new Processor<Trinity<ProblemDescriptor, LocalInspectionToolWrapper,ProgressIndicator>>() {
    private final InspectionProfile inspectionProfile = InspectionProjectProfileManager.getInstance(myProject).getInspectionProfile();
    private final InjectedLanguageManager ilManager = InjectedLanguageManager.getInstance(myProject);
    private final List<HighlightInfo> infos = new ArrayList<HighlightInfo>(2);
    private final PsiDocumentManager documentManager = PsiDocumentManager.getInstance(myProject);
    @Override
    public boolean process(Trinity<ProblemDescriptor, LocalInspectionToolWrapper,ProgressIndicator> trinity) {
      ProgressIndicator indicator = trinity.getThird();
      if (indicator.isCanceled()) {
        return false;
      }

      ProblemDescriptor descriptor = trinity.first;
      LocalInspectionToolWrapper tool = trinity.second;
      PsiElement psiElement = descriptor.getPsiElement();
      if (psiElement == null) return true;
      PsiFile file = psiElement.getContainingFile();
      Document thisDocument = documentManager.getDocument(file);

      HighlightSeverity severity = inspectionProfile.getErrorLevel(HighlightDisplayKey.find(tool.getShortName()), file).getSeverity();

      infos.clear();
      createHighlightsForDescriptor(infos, emptyActionRegistered, ilManager, file, thisDocument, tool, severity, descriptor, psiElement);
      for (HighlightInfo info : infos) {
        final EditorColorsScheme colorsScheme = getColorsScheme();
        UpdateHighlightersUtil.addHighlighterToEditorIncrementally(myProject, myDocument, myFile, myStartOffset, myEndOffset,
                                                                   info, colorsScheme, getId(), ranges2markersCache);
      }

      return true;
    }
  }, myProject.getDisposed(), 200);

  private final Set<Pair<TextRange, String>> emptyActionRegistered = Collections.synchronizedSet(new THashSet<Pair<TextRange, String>>());

  private void addDescriptorIncrementally(@NotNull final ProblemDescriptor descriptor,
                                          @NotNull final LocalInspectionToolWrapper tool,
                                          @NotNull final ProgressIndicator indicator) {
    if (myIgnoreSuppressed && SuppressionUtil.inspectionResultSuppressed(descriptor.getPsiElement(), tool.getTool())) {
      return;
    }
    myTransferToEDTQueue.offer(Trinity.create(descriptor, tool, indicator));
  }

  private void appendDescriptors(@NotNull PsiFile file, @NotNull List<ProblemDescriptor> descriptors, @NotNull LocalInspectionToolWrapper tool) {
    for (ProblemDescriptor descriptor : descriptors) {
      if (descriptor == null) {
        LOG.error("null descriptor. all descriptors(" + descriptors.size() +"): " +
                   descriptors + "; file: " + file + " (" + file.getVirtualFile() +"); tool: " + tool);
      }
    }
    InspectionResult res = new InspectionResult(tool, descriptors);
    appendResult(file, res);
  }

  private void appendResult(@NotNull PsiFile file, @NotNull InspectionResult res) {
    List<InspectionResult> resultList = result.get(file);
    if (resultList == null) {
      resultList = ConcurrencyUtil.cacheOrGet(result, file, new ArrayList<InspectionResult>());
    }
    synchronized (resultList) {
      resultList.add(res);
    }
  }

  @Override
  protected void applyInformationWithProgress() {
    UpdateHighlightersUtil.setHighlightersToEditor(myProject, myDocument, myStartOffset, myEndOffset, myInfos, getColorsScheme(), getId());
  }

  private void addHighlightsFromResults(@NotNull List<HighlightInfo> outInfos, @NotNull ProgressIndicator indicator) {
    InspectionProfile inspectionProfile = InspectionProjectProfileManager.getInstance(myProject).getInspectionProfile();
    PsiDocumentManager documentManager = PsiDocumentManager.getInstance(myProject);
    InjectedLanguageManager ilManager = InjectedLanguageManager.getInstance(myProject);
    Set<Pair<TextRange, String>> emptyActionRegistered = new THashSet<Pair<TextRange, String>>();

    for (Map.Entry<PsiFile, List<InspectionResult>> entry : result.entrySet()) {
      indicator.checkCanceled();
      PsiFile file = entry.getKey();
      Document documentRange = documentManager.getDocument(file);
      if (documentRange == null) continue;
      List<InspectionResult> resultList = entry.getValue();
      synchronized (resultList) {
        for (InspectionResult inspectionResult : resultList) {
          indicator.checkCanceled();
          LocalInspectionToolWrapper tool = inspectionResult.tool;
          HighlightSeverity severity = inspectionProfile.getErrorLevel(HighlightDisplayKey.find(tool.getShortName()), file).getSeverity();
          for (ProblemDescriptor descriptor : inspectionResult.foundProblems) {
            indicator.checkCanceled();
            PsiElement element = descriptor.getPsiElement();
            createHighlightsForDescriptor(outInfos, emptyActionRegistered, ilManager, file, documentRange, tool, severity, descriptor, element);
          }
        }
      }
    }
  }

  private void createHighlightsForDescriptor(@NotNull List<HighlightInfo> outInfos,
                                             @NotNull Set<Pair<TextRange, String>> emptyActionRegistered,
                                             @NotNull InjectedLanguageManager ilManager,
                                             @NotNull PsiFile file,
                                             @NotNull Document documentRange,
                                             @NotNull LocalInspectionToolWrapper tool,
                                             @NotNull HighlightSeverity severity,
                                             @NotNull ProblemDescriptor descriptor,
                                             PsiElement element) {
    if (element == null) return;
    if (myIgnoreSuppressed && SuppressionUtil.inspectionResultSuppressed(element, tool.getTool())) return;
    HighlightInfoType level = ProblemDescriptorUtil.highlightTypeFromDescriptor(descriptor, severity, mySeverityRegistrar);
    HighlightInfo info = createHighlightInfo(descriptor, tool, level, emptyActionRegistered, element);
    if (info == null) return;

    if (file == myFile) {
      // not injected
      outInfos.add(info);
      return;
    }
    // todo we got to separate our "internal" prefixes/suffixes from user-defined ones
    // todo in the latter case the errors should be highlighted, otherwise not
    List<TextRange> editables = ilManager.intersectWithAllEditableFragments(file, new TextRange(info.startOffset, info.endOffset));
    for (TextRange editable : editables) {
      TextRange hostRange = ((DocumentWindow)documentRange).injectedToHost(editable);
      int start = hostRange.getStartOffset();
      int end = hostRange.getEndOffset();
      HighlightInfo.Builder builder = HighlightInfo.newHighlightInfo(info.type).range(element, start, end);
      String description = info.getDescription();
      if (description != null) {
        builder.description(description);
      }
      String toolTip = info.getToolTip();
      if (toolTip != null) {
        builder.escapedToolTip(toolTip);
      }
      HighlightInfo patched = builder.createUnconditionally();
      if (patched.startOffset != patched.endOffset || info.startOffset == info.endOffset) {
        patched.setFromInjection(true);
        registerQuickFixes(tool, descriptor, patched, emptyActionRegistered);
        outInfos.add(patched);
      }
    }
  }

  @Nullable
  private HighlightInfo createHighlightInfo(@NotNull ProblemDescriptor descriptor,
                                            @NotNull LocalInspectionToolWrapper tool,
                                            @NotNull HighlightInfoType level,
                                            @NotNull Set<Pair<TextRange, String>> emptyActionRegistered,
                                            @NotNull PsiElement element) {
    @NonNls String message = ProblemDescriptorUtil.renderDescriptionMessage(descriptor, element);

    final HighlightDisplayKey key = HighlightDisplayKey.find(tool.getShortName());
    final InspectionProfile inspectionProfile = myProfileWrapper.getInspectionProfile();
    if (!inspectionProfile.isToolEnabled(key, myFile)) return null;

    HighlightInfoType type = new HighlightInfoType.HighlightInfoTypeImpl(level.getSeverity(element), level.getAttributesKey());
    final String plainMessage = message.startsWith("<html>") ? StringUtil.unescapeXml(XmlStringUtil.stripHtml(message).replaceAll("<[^>]*>", "")) : message;
    @NonNls final String link = " <a "
                                +"href=\"#inspection/" + tool.getShortName() + "\""
                                + (UIUtil.isUnderDarcula() ? " color=\"7AB4C9\" " : "")
                                +">" + DaemonBundle.message("inspection.extended.description")
                                +"</a> " + myShortcutText;

    @NonNls String tooltip = null;
    if (descriptor.showTooltip()) {
      if (message.startsWith("<html>")) {
        tooltip = XmlStringUtil.wrapInHtml(XmlStringUtil.stripHtml(message) + link);
      }
      else {
        tooltip = XmlStringUtil.wrapInHtml(XmlStringUtil.escapeString(message) + link);
      }
    }
    HighlightInfo highlightInfo = highlightInfoFromDescriptor(descriptor, type, plainMessage, tooltip,element);
    if (highlightInfo != null) {
      registerQuickFixes(tool, descriptor, highlightInfo, emptyActionRegistered);
    }
    return highlightInfo;
  }

  private static void registerQuickFixes(@NotNull LocalInspectionToolWrapper tool,
                                         @NotNull ProblemDescriptor descriptor,
                                         @NotNull HighlightInfo highlightInfo,
                                         @NotNull Set<Pair<TextRange,String>> emptyActionRegistered) {
    final HighlightDisplayKey key = HighlightDisplayKey.find(tool.getShortName());
    boolean needEmptyAction = true;
    final QuickFix[] fixes = descriptor.getFixes();
    if (fixes != null && fixes.length > 0) {
      for (int k = 0; k < fixes.length; k++) {
        if (fixes[k] != null) { // prevent null fixes from var args
          QuickFixAction.registerQuickFixAction(highlightInfo, QuickFixWrapper.wrap(descriptor, k), key);
          needEmptyAction = false;
        }
      }
    }
    HintAction hintAction = descriptor instanceof ProblemDescriptorImpl ? ((ProblemDescriptorImpl)descriptor).getHintAction() : null;
    if (hintAction != null) {
      QuickFixAction.registerQuickFixAction(highlightInfo, hintAction, key);
      needEmptyAction = false;
    }
    if (((ProblemDescriptorBase)descriptor).getEnforcedTextAttributes() != null) {
      needEmptyAction = false;
    }
    if (needEmptyAction && emptyActionRegistered.add(Pair.<TextRange, String>create(highlightInfo.getFixTextRange(), tool.getShortName()))) {
      EmptyIntentionAction emptyIntentionAction = new EmptyIntentionAction(tool.getDisplayName());
      QuickFixAction.registerQuickFixAction(highlightInfo, emptyIntentionAction, key);
    }
  }

  @NotNull
  private static List<PsiElement> getElementsFrom(@NotNull PsiFile file) {
    final FileViewProvider viewProvider = file.getViewProvider();
    final Set<PsiElement> result = new LinkedHashSet<PsiElement>();
    final PsiElementVisitor visitor = new PsiRecursiveElementVisitor() {
      @Override public void visitElement(PsiElement element) {
        ProgressManager.checkCanceled();
        PsiElement child = element.getFirstChild();
        if (child == null) {
          // leaf element
        }
        else {
          // composite element
          while (child != null) {
            child.accept(this);
            result.add(child);

            child = child.getNextSibling();
          }
        }
      }
    };
    for (Language language : viewProvider.getLanguages()) {
      final PsiFile psiRoot = viewProvider.getPsi(language);
      if (psiRoot == null || !HighlightingLevelManager.getInstance(file.getProject()).shouldInspect(psiRoot)) {
        continue;
      }
      psiRoot.accept(visitor);
      result.add(psiRoot);
    }
    return new ArrayList<PsiElement>(result);
  }


  @NotNull
  private List<LocalInspectionToolWrapper> getHighlightingLocalInspectionTools(@NotNull InspectionProfileWrapper profile, PsiElement element) {
    List<LocalInspectionToolWrapper> enabled = new ArrayList<LocalInspectionToolWrapper>();
    final InspectionToolWrapper[] toolWrappers = profile.getInspectionTools(element);
    InspectionProfileWrapper.checkInspectionsDuplicates(toolWrappers);
    Language language = myFile.getLanguage();
    for (InspectionToolWrapper toolWrapper : toolWrappers) {
      if (!profile.isToolEnabled(HighlightDisplayKey.find(toolWrapper.getShortName()), element)) continue;
      LocalInspectionToolWrapper wrapper = null;
      if (toolWrapper instanceof LocalInspectionToolWrapper) {
        wrapper = (LocalInspectionToolWrapper)toolWrapper;
      }
      else if (toolWrapper instanceof GlobalInspectionToolWrapper) {
        final GlobalInspectionToolWrapper globalInspectionToolWrapper = (GlobalInspectionToolWrapper)toolWrapper;
        wrapper = globalInspectionToolWrapper.getSharedLocalInspectionToolWrapper();
      }
      if (wrapper == null) continue;
      if (myIgnoreSuppressed) {
        if (wrapper.isApplicable(language) && SuppressionUtil.inspectionResultSuppressed(myFile, wrapper.getTool())) {
          continue;
        }
      }
      enabled.add(wrapper);
    }
    return enabled;
  }

  @NotNull
  List<LocalInspectionToolWrapper> getInspectionTools(@NotNull InspectionProfileWrapper profile) {
    return getHighlightingLocalInspectionTools(profile, myFile);
  }

  private void doInspectInjectedPsi(@NotNull PsiFile injectedPsi,
                                    final boolean isOnTheFly,
                                    @NotNull final ProgressIndicator indicator,
                                    @NotNull InspectionManagerEx iManager,
                                    final boolean inVisibleRange,
                                    @NotNull List<LocalInspectionToolWrapper> wrappers,
                                    boolean checkDumbAwareness) {
    final PsiElement host = InjectedLanguageManager.getInstance(injectedPsi.getProject()).getInjectionHost(injectedPsi);

    final List<PsiElement> elements = getElementsFrom(injectedPsi);
    if (elements.isEmpty()) {
      return;
    }
    MultiMap<LocalInspectionToolWrapper, String> toolToLanguages =
      getToolsForElements(wrappers, checkDumbAwareness, elements, Collections.<PsiElement>emptyList());
    for (final Map.Entry<LocalInspectionToolWrapper, Collection<String>> pair : toolToLanguages.entrySet()) {
      indicator.checkCanceled();
      final LocalInspectionToolWrapper wrapper = pair.getKey();
      final LocalInspectionTool tool = wrapper.getTool();
      if (host != null && myIgnoreSuppressed && SuppressionUtil.inspectionResultSuppressed(host, tool)) {
        continue;
      }
      ProblemsHolder holder = new ProblemsHolder(iManager, injectedPsi, isOnTheFly) {
        @Override
        public void registerProblem(@NotNull ProblemDescriptor descriptor) {
          super.registerProblem(descriptor);
          if (isOnTheFly && inVisibleRange) {
            addDescriptorIncrementally(descriptor, wrapper, indicator);
          }
        }
      };

      LocalInspectionToolSession injSession = new LocalInspectionToolSession(injectedPsi, 0, injectedPsi.getTextLength());
      Collection<String> languages = pair.getValue();
      InspectionEngine.createVisitorAndAcceptElements(tool, holder, isOnTheFly, injSession, elements, languages);
      tool.inspectionFinished(injSession, holder);
      List<ProblemDescriptor> problems = holder.getResults();
      if (!problems.isEmpty()) {
        appendDescriptors(injectedPsi, problems, wrapper);
      }
    }
  }

  @Override
  @NotNull
  public List<HighlightInfo> getInfos() {
    return myInfos;
  }

  private static class InspectionResult {
    @NotNull public final LocalInspectionToolWrapper tool;
    @NotNull public final List<ProblemDescriptor> foundProblems;

    private InspectionResult(@NotNull LocalInspectionToolWrapper tool, @NotNull List<ProblemDescriptor> foundProblems) {
      this.tool = tool;
      this.foundProblems = foundProblems;
    }
  }

  private static class InspectionContext {
    private InspectionContext(@NotNull LocalInspectionToolWrapper tool,
                              @NotNull ProblemsHolder holder,
                              @NotNull PsiElementVisitor visitor,
                              @Nullable Collection<String> languageIds) {
      this.tool = tool;
      this.holder = holder;
      this.visitor = visitor;
      this.languageIds = languageIds;
    }

    @NotNull final LocalInspectionToolWrapper tool;
    @NotNull final ProblemsHolder holder;
    @NotNull final PsiElementVisitor visitor;
    @Nullable final Collection<String> languageIds;
  }
}
