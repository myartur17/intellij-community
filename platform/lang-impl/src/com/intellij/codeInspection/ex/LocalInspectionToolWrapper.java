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

package com.intellij.codeInspection.ex;

import com.intellij.analysis.AnalysisScope;
import com.intellij.codeHighlighting.HighlightDisplayLevel;
import com.intellij.codeInspection.*;
import com.intellij.codeInspection.reference.RefElement;
import com.intellij.codeInspection.reference.RefEntity;
import com.intellij.codeInspection.reference.RefManagerImpl;
import com.intellij.codeInspection.ui.InspectionResultsView;
import com.intellij.codeInspection.ui.InspectionTreeNode;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.InvalidDataException;
import com.intellij.openapi.util.WriteExternalException;
import com.intellij.psi.*;
import com.intellij.util.TripleFunction;
import com.intellij.util.containers.*;
import com.intellij.util.containers.HashSet;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.tree.DefaultTreeModel;
import java.util.*;
import java.util.HashMap;

/**
 * @author max
 */
public final class LocalInspectionToolWrapper extends DescriptorProviderInspection {
  private static final Logger LOG = Logger.getInstance("#com.intellij.codeInspection.ex.LocalInspectionToolWrapper");

  @NotNull private final LocalInspectionTool myTool;

  public LocalInspectionToolWrapper(@NotNull LocalInspectionTool tool) {
    myTool = tool;
  }

  @NotNull
  public LocalInspectionTool getTool() {
    return myTool;
  }

  public void processFile(PsiFile file, final boolean filterSuppressed, final InspectionManager manager) {
    processFile(file, filterSuppressed, manager, false);
  }

  public void processFile(final PsiFile file, final boolean filterSuppressed, final InspectionManager manager, final boolean isOnTheFly) {
    final ProblemsHolder holder = new ProblemsHolder(manager, file, isOnTheFly);
    LocalInspectionToolSession session = new LocalInspectionToolSession(file, 0, file.getTextLength());
    final PsiElementVisitor customVisitor = myTool.buildVisitor(holder, isOnTheFly, session);
    LOG.assertTrue(!(customVisitor instanceof PsiRecursiveElementVisitor), "The visitor returned from LocalInspectionTool.buildVisitor() must not be recursive");

    myTool.inspectionStarted(session, isOnTheFly);

    file.accept(new PsiRecursiveElementWalkingVisitor() {
      @Override public void visitElement(PsiElement element) {
        element.accept(customVisitor);
        super.visitElement(element);
      }
    });

    myTool.inspectionFinished(session, holder);

    addProblemDescriptors(holder.getResults(), filterSuppressed);
  }

  @NotNull
  public JobDescriptor[] getJobDescriptors(GlobalInspectionContext context) {
    return ((GlobalInspectionContextImpl)context).LOCAL_ANALYSIS_ARRAY;
  }

  public void addProblemDescriptors(List<ProblemDescriptor> descriptors, final boolean filterSuppressed) {
    addProblemDescriptors(descriptors, filterSuppressed, getContext(), myTool, CONVERT, this);
  }
  private static final TripleFunction<LocalInspectionTool, PsiElement, GlobalInspectionContext,RefElement> CONVERT = new TripleFunction<LocalInspectionTool, PsiElement, GlobalInspectionContext,RefElement>() {
    @Override
    public RefElement fun(LocalInspectionTool tool, PsiElement elt, GlobalInspectionContext context) {
      final PsiNamedElement problemElement = tool.getProblemElement(elt);

      RefElement refElement = context.getRefManager().getReference(problemElement);
      if (refElement == null && problemElement != null) {  // no need to lose collected results
        refElement = GlobalInspectionUtil.retrieveRefElement(elt, context);
      }
      return refElement;
    }
  };

  @Override
  protected void addProblemElement(RefEntity refElement, boolean filterSuppressed, CommonProblemDescriptor... descriptions) {
    super.addProblemElement(refElement, filterSuppressed, descriptions);
    final InspectionResultsView view = getContext().getView();
    if (view != null && refElement instanceof RefElement) {
      if (myToolNode == null) {
        final HighlightSeverity currentSeverity = getCurrentSeverity((RefElement)refElement);
        view.addTool(this, HighlightDisplayLevel.find(currentSeverity), getContext().getUIOptions().GROUP_BY_SEVERITY);
      }
      final HashMap<RefEntity, CommonProblemDescriptor[]> problems = new HashMap<RefEntity, CommonProblemDescriptor[]>();
      problems.put(refElement, descriptions);
      final HashMap<String, Set<RefEntity>> contents = new HashMap<String, Set<RefEntity>>();
      final String groupName = refElement.getRefManager().getGroupName((RefElement)refElement);
      Set<RefEntity> content = contents.get(groupName);
      if (content == null) {
        content = new HashSet<RefEntity>();
        contents.put(groupName, content);
      }
      content.add(refElement);
      view.getProvider().appendToolNodeContent(myToolNode,
                                               (InspectionTreeNode)myToolNode.getParent(), getContext().getUIOptions().SHOW_STRUCTURE,
                                               contents, problems);
      SwingUtilities.invokeLater(new Runnable() {
        public void run() {
          getContext().addView(view);
          ((DefaultTreeModel)view.getTree().getModel()).reload(myToolNode);
        }
      });
    }
  }

  public static void addProblemDescriptors(List<ProblemDescriptor> descriptors,
                                           boolean filterSuppressed,
                                           @NotNull GlobalInspectionContextImpl context,
                                           LocalInspectionTool tool,
                                           @NotNull TripleFunction<LocalInspectionTool, PsiElement, GlobalInspectionContext, RefElement> getProblemElementFunction,
                                           @NotNull DescriptorProviderInspection dpi) {
    if (descriptors == null || descriptors.isEmpty()) return;

    Map<RefElement, List<ProblemDescriptor>> problems = new HashMap<RefElement, List<ProblemDescriptor>>();
    final RefManagerImpl refManager = (RefManagerImpl)context.getRefManager();
    for (ProblemDescriptor descriptor : descriptors) {
      final PsiElement elt = descriptor.getPsiElement();
      if (elt == null) continue;
      if (filterSuppressed) {
        if (refManager.isDeclarationsFound()
            && (context.isSuppressed(elt, tool.getID()) || tool.getAlternativeID() != null && context.isSuppressed(elt, tool.getAlternativeID()))) {
          continue;
        }
        if (InspectionManagerEx.inspectionResultSuppressed(elt, tool)) continue;
      }


      RefElement refElement = getProblemElementFunction.fun(tool, elt, context);

      List<ProblemDescriptor> elementProblems = problems.get(refElement);
      if (elementProblems == null) {
        elementProblems = new ArrayList<ProblemDescriptor>();
        problems.put(refElement, elementProblems);
      }
      elementProblems.add(descriptor);
    }

    for (Map.Entry<RefElement, List<ProblemDescriptor>> entry : problems.entrySet()) {
      final List<ProblemDescriptor> problemDescriptors = entry.getValue();
      dpi.addProblemElement(entry.getKey(),
                            filterSuppressed,
                            problemDescriptors.toArray(new CommonProblemDescriptor[problemDescriptors.size()]));
    }
  }

  public void runInspection(@NotNull AnalysisScope scope, @NotNull final InspectionManager manager) {
    LOG.assertTrue(ApplicationManager.getApplication().isUnitTestMode());
    scope.accept(new PsiRecursiveElementVisitor() {
      @Override public void visitFile(PsiFile file) {
        processFile(file, true, manager);
      }
    });
  }

  @NotNull
  public String getDisplayName() {
    return myTool.getDisplayName();
  }

  @NotNull
  public String getGroupDisplayName() {
    return myTool.getGroupDisplayName();
  }

  @NotNull
  public String getShortName() {
    return myTool.getShortName();
  }

  public boolean isEnabledByDefault() {
    return myTool.isEnabledByDefault();
  }

  @NotNull
   @Override
   public String[] getGroupPath() {
     return myTool.getGroupPath();
   }


  @NotNull
  public HighlightDisplayLevel getDefaultLevel() {
    return myTool.getDefaultLevel();
  }

  public void readSettings(Element element) throws InvalidDataException {
    myTool.readSettings(element);
  }

  public void writeSettings(Element element) throws WriteExternalException {
    myTool.writeSettings(element);
  }

  public JComponent createOptionsPanel() {
    return myTool.createOptionsPanel();    
  }

  public void projectOpened(Project project) {
    myTool.projectOpened(project);
  }

  public void projectClosed(Project project) {
    myTool.projectClosed(project);
  }

  protected Class<? extends InspectionProfileEntry> getDescriptionContextClass() {
    return myTool.getClass();
  }

  @Nullable
  public String getStaticDescription() {
    return myTool.getStaticDescription();
  }

  @Nullable
  public SuppressIntentionAction[] getSuppressActions() {
    if (myTool instanceof CustomSuppressableInspectionTool) {
      return ((CustomSuppressableInspectionTool)myTool).getSuppressActions(null);
    }
    return super.getSuppressActions();
  }

  public boolean isUnfair() {
    return myTool instanceof UnfairLocalInspectionTool;
  }
}
