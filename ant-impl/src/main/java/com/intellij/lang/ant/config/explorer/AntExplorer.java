/*
 * Copyright 2000-2012 JetBrains s.r.o.
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
package com.intellij.lang.ant.config.explorer;

import com.intellij.lang.ant.AntBundle;
import com.intellij.lang.ant.config.*;
import com.intellij.lang.ant.config.actions.AntBuildFilePropertiesAction;
import com.intellij.lang.ant.config.actions.RemoveBuildFileAction;
import com.intellij.lang.ant.config.execution.ExecutionHandler;
import com.intellij.lang.ant.config.impl.*;
import com.intellij.lang.ant.config.impl.configuration.BuildFilePropertiesPanel;
import consulo.apache.ant.config.actions.AntGroupManagerActionGroup;
import consulo.apache.ant.config.actions.RemoveGroupsAction;
import consulo.application.AllIcons;
import consulo.application.ApplicationManager;
import consulo.dataContext.DataContext;
import consulo.dataContext.DataManager;
import consulo.dataContext.DataSink;
import consulo.dataContext.UiDataProvider;
import consulo.disposer.Disposable;
import consulo.disposer.Disposer;
import consulo.execution.event.RunManagerListener;
import consulo.execution.event.RunManagerListenerEvent;
import consulo.fileChooser.FileChooserDescriptor;
import consulo.fileChooser.IdeaFileChooser;
import consulo.language.editor.LangDataKeys;
import consulo.language.editor.PlatformDataKeys;
import consulo.language.psi.PsiElement;
import consulo.navigation.Navigatable;
import consulo.navigation.OpenFileDescriptorFactory;
import consulo.platform.base.icon.PlatformIconGroup;
import consulo.project.Project;
import consulo.ui.ex.SimpleTextAttributes;
import consulo.ui.ex.TreeExpander;
import consulo.ui.ex.action.*;
import consulo.ui.ex.awt.Messages;
import consulo.ui.ex.awt.PopupHandler;
import consulo.ui.ex.awt.ScrollPaneFactory;
import consulo.ui.ex.awt.SimpleToolWindowPanel;
import consulo.ui.ex.awt.dnd.FileCopyPasteUtil;
import consulo.ui.ex.awt.event.DoubleClickListener;
import consulo.ui.ex.awt.speedSearch.TreeSpeedSearch;
import consulo.ui.ex.awt.tree.ColoredTreeCellRenderer;
import consulo.ui.ex.awt.tree.Tree;
import consulo.ui.ex.awt.tree.TreeUtil;
import consulo.ui.ex.keymap.Keymap;
import consulo.ui.ex.keymap.KeymapManager;
import consulo.ui.ex.keymap.event.KeymapManagerListener;
import consulo.util.collection.ArrayUtil;
import consulo.util.collection.ContainerUtil;
import consulo.virtualFileSystem.VirtualFile;
import consulo.virtualFileSystem.util.VirtualFileUtil;
import consulo.xml.language.XmlFileType;
import consulo.xml.dom.DomEventListener;
import consulo.xml.dom.DomManager;
import consulo.xml.dom.DomEvent;
import jakarta.annotation.Nullable;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

public class AntExplorer extends SimpleToolWindowPanel implements UiDataProvider, Disposable {
  private Project myProject;
  private AntExplorerTreeBuilder myBuilder;
  private Tree myTree;
  private KeymapListener myKeymapListener;
  private final AntBuildFilePropertiesAction myAntBuildFilePropertiesAction;
  private AntConfiguration myConfig;

  private final TreeExpander myTreeExpander = new TreeExpander() {
    public void expandAll() {
      myBuilder.expandAll();
    }

    public boolean canExpand() {
      final AntConfiguration config = myConfig;
      return config != null && config.getBuildFiles().length != 0;
    }

    public void collapseAll() {
      myBuilder.collapseAll();
    }

    public boolean canCollapse() {
      return canExpand();
    }
  };

  public AntExplorer(final Project project) {
    super(true, true);
    setTransferHandler(new MyTransferHandler());
    myProject = project;
    myConfig = AntConfiguration.getInstance(project);
    final DefaultTreeModel model = new DefaultTreeModel(new DefaultMutableTreeNode());
    myTree = new Tree(model);
    myTree.setRootVisible(false);
    myTree.setShowsRootHandles(true);
    myTree.setCellRenderer(new NodeRenderer());
    myBuilder = new AntExplorerTreeBuilder(project, myTree, model);
    myBuilder.setTargetsFiltered(AntConfigurationBase.getInstance(project).isFilterTargets());
    myBuilder.setModuleGrouping(AntConfigurationBase.getInstance(project).isModuleGrouping());
    TreeUtil.installActions(myTree);
    new TreeSpeedSearch(myTree);
    myTree.addMouseListener(new PopupHandler() {
      public void invokePopup(final Component comp, final int x, final int y) {
        popupInvoked(comp, x, y);
      }
    });

    new DoubleClickListener() {
      @Override
      protected boolean onDoubleClick(MouseEvent e) {
        final int eventY = e.getY();
        final int row = myTree.getClosestRowForLocation(e.getX(), eventY);
        if (row >= 0) {
          final Rectangle bounds = myTree.getRowBounds(row);
          if (bounds != null && eventY > bounds.getY() && eventY < bounds.getY() + bounds.getHeight()) {
            runSelection(DataManager.getInstance().getDataContext(myTree));
            return true;
          }
        }
        return false;
      }
    }.installOn(myTree);

    myTree.registerKeyboardAction(new AbstractAction() {
      public void actionPerformed(ActionEvent e) {
        runSelection(DataManager.getInstance().getDataContext(myTree));
      }
    }, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), WHEN_FOCUSED);
    myTree.setLineStyleAngled();
    myAntBuildFilePropertiesAction = new AntBuildFilePropertiesAction(this);
    setToolbar(createToolbarPanel());
    setContent(ScrollPaneFactory.createScrollPane(myTree));
    ToolTipManager.sharedInstance().registerComponent(myTree);
    myKeymapListener = new KeymapListener();

    DomManager.getDomManager(project).addDomEventListener(new DomEventListener() {
      public void eventOccured(DomEvent event) {
        myBuilder.queueUpdate();
      }
    }, this);

    myProject.getMessageBus().connect().subscribe(RunManagerListener.class, new RunManagerListener() {
      @Override
      public void beforeRunTasksChanged(RunManagerListenerEvent event) {
        myBuilder.queueUpdate();
      }
    });
  }

  public void dispose() {
    final KeymapListener listener = myKeymapListener;
    if (listener != null) {
      myKeymapListener = null;
      listener.stopListen();
    }

    final AntExplorerTreeBuilder builder = myBuilder;
    if (builder != null) {
      Disposer.dispose(builder);
      myBuilder = null;
    }

    final Tree tree = myTree;
    if (tree != null) {
      ToolTipManager.sharedInstance().unregisterComponent(tree);
      for (KeyStroke keyStroke : tree.getRegisteredKeyStrokes()) {
        tree.unregisterKeyboardAction(keyStroke);
      }
      myTree = null;
    }

    myProject = null;
    myConfig = null;
  }

  private JPanel createToolbarPanel() {
    final DefaultActionGroup group = new DefaultActionGroup();
    group.add(new AddAction());
    group.add(new RemoveAction());
    group.add(new RunAction());
    group.add(myAntBuildFilePropertiesAction);
    group.addSeparator();
    group.add(new ShowAllTargetsAction());
    group.add(new ShowModuleGrouping());
    AnAction action = CommonActionsManager.getInstance().createExpandAllAction(myTreeExpander, this);
    action.getTemplatePresentation().setDescription(AntBundle.message("ant.explorer.expand.all.nodes.action.description"));
    group.add(action);
    action = CommonActionsManager.getInstance().createCollapseAllAction(myTreeExpander, this);
    action.getTemplatePresentation().setDescription(AntBundle.message("ant.explorer.collapse.all.nodes.action.description"));
    group.add(action);

    final ActionToolbar actionToolBar = ActionManager.getInstance().createActionToolbar(ActionPlaces.ANT_EXPLORER_TOOLBAR, group, true);
    actionToolBar.setTargetComponent(this);
    final JPanel buttonsPanel = new JPanel(new BorderLayout());
    buttonsPanel.add(actionToolBar.getComponent(), BorderLayout.CENTER);
    return buttonsPanel;
  }

  private void addBuildFile() {
    final FileChooserDescriptor descriptor = createXmlDescriptor();
    descriptor.setTitle(AntBundle.message("select.ant.build.file.dialog.title"));
    descriptor.setDescription(AntBundle.message("select.ant.build.file.dialog.description"));
    final VirtualFile[] files = IdeaFileChooser.chooseFiles(descriptor, myProject, null);
    addBuildFile(files);
  }

  private void addBuildFile(final VirtualFile[] files) {
    if (files.length == 0) {
      return;
    }
    ApplicationManager.getApplication().invokeLater(new Runnable() {
      public void run() {
        final AntConfiguration antConfiguration = myConfig;
        if (antConfiguration == null) {
          return;
        }
        final List<VirtualFile> ignoredFiles = new ArrayList<consulo.virtualFileSystem.VirtualFile>();
        for (consulo.virtualFileSystem.VirtualFile file : files) {
          try {
            antConfiguration.addBuildFile(file);
          }
          catch (AntNoFileException e) {
            ignoredFiles.add(e.getFile());
          }
        }
        if (ignoredFiles.size() != 0) {
          String messageText;
          final StringBuilder message = new StringBuilder();
          String separator = "";
          for (final consulo.virtualFileSystem.VirtualFile virtualFile : ignoredFiles) {
            message.append(separator);
            message.append(virtualFile.getPresentableUrl());
            separator = "\n";
          }
          messageText = message.toString();
          Messages.showWarningDialog(myProject, messageText, AntBundle.message("cannot.add.ant.files.dialog.title"));
        }
      }
    });
  }

  public void removeBuildFile() {
    final AntBuildFile buildFile = getCurrentBuildFile();
    if (buildFile == null) {
      return;
    }
    final String fileName = buildFile.getPresentableUrl();
    final int result = Messages.showYesNoDialog(myProject, AntBundle.message("remove.the.reference.to.file.confirmation.text", fileName),
                                                AntBundle.message("confirm.remove.dialog.title"), Messages.getQuestionIcon());
    if (result != 0) {
      return;
    }
    myConfig.removeBuildFile(buildFile);
  }

  public void setBuildFileProperties() {
    final AntBuildFileBase buildFile = getCurrentBuildFile();
    if (buildFile != null && BuildFilePropertiesPanel.editBuildFile(buildFile, myProject)) {
      myConfig.updateBuildFile(buildFile);
      myBuilder.queueUpdate();
      myTree.repaint();
    }
  }

  private void runSelection(final DataContext dataContext) {
    if (!canRunSelection()) {
      return;
    }
    final AntBuildFileBase buildFile = getCurrentBuildFile();
    if (buildFile != null) {
      final TreePath[] paths = myTree.getSelectionPaths();
      final String[] targets = getTargetNamesFromPaths(paths);
      ExecutionHandler.runBuild(buildFile, targets, dataContext, Collections.<BuildFileProperty>emptyList(), AntBuildListener.NULL);
    }
  }

  private boolean canRunSelection() {
    if (myTree == null) {
      return false;
    }
    final TreePath[] paths = myTree.getSelectionPaths();
    if (paths == null) {
      return false;
    }
    final AntBuildFile buildFile = getCurrentBuildFile();
    if (buildFile == null || !buildFile.exists()) {
      return false;
    }
    for (final TreePath path : paths) {
      final DefaultMutableTreeNode node = (DefaultMutableTreeNode)path.getLastPathComponent();
      final Object userObject = node.getUserObject();
      final AntBuildFileNodeDescriptor buildFileNodeDescriptor;
      if (userObject instanceof AntTargetNodeDescriptor) {
        buildFileNodeDescriptor = (AntBuildFileNodeDescriptor)((DefaultMutableTreeNode)node.getParent()).getUserObject();
      }
      else if (userObject instanceof AntBuildFileNodeDescriptor) {
        buildFileNodeDescriptor = (AntBuildFileNodeDescriptor)userObject;
      }
      else {
        buildFileNodeDescriptor = null;
      }
      if (buildFileNodeDescriptor == null || buildFileNodeDescriptor.getBuildFile() != buildFile) {
        return false;
      }
    }
    return true;
  }

  private static String[] getTargetNamesFromPaths(TreePath[] paths) {
    final List<String> targets = new ArrayList<String>();
    for (final TreePath path : paths) {
      final Object userObject = ((DefaultMutableTreeNode)path.getLastPathComponent()).getUserObject();
      if (!(userObject instanceof AntTargetNodeDescriptor)) {
        continue;
      }
      final AntBuildTarget target = ((AntTargetNodeDescriptor)userObject).getTarget();
      if (target instanceof MetaTarget) {
        consulo.util.collection.ContainerUtil.addAll(targets, ((MetaTarget)target).getTargetNames());
      }
      else {
        targets.add(target.getName());
      }
    }
    return ArrayUtil.toStringArray(targets);
  }

  private static AntBuildTarget[] getTargetObjectsFromPaths(TreePath[] paths) {
    final List<AntBuildTargetBase> targets = new ArrayList<AntBuildTargetBase>();
    for (final TreePath path : paths) {
      final Object userObject = ((DefaultMutableTreeNode)path.getLastPathComponent()).getUserObject();
      if (!(userObject instanceof AntTargetNodeDescriptor)) {
        continue;
      }
      final AntBuildTargetBase target = ((AntTargetNodeDescriptor)userObject).getTarget();
      targets.add(target);

    }
    return targets.toArray(new AntBuildTargetBase[targets.size()]);
  }

  public boolean isBuildFileSelected() {
    if (myProject == null) return false;
    final AntBuildFileBase file = getCurrentBuildFile();
    return file != null && file.exists();
  }

  @Nullable
  private AntBuildFileBase getCurrentBuildFile() {
    final AntBuildFileNodeDescriptor descriptor = getCurrentBuildFileNodeDescriptor();
    return (AntBuildFileBase)((descriptor == null) ? null : descriptor.getBuildFile());
  }

  @Nullable
  private AntBuildFileNodeDescriptor getCurrentBuildFileNodeDescriptor() {
    if (myTree == null) {
      return null;
    }
    final TreePath path = myTree.getSelectionPath();
    if (path == null) {
      return null;
    }
    DefaultMutableTreeNode node = (DefaultMutableTreeNode)path.getLastPathComponent();
    while (node != null) {
      final Object userObject = node.getUserObject();
      if (userObject instanceof AntBuildFileNodeDescriptor) {
        return (AntBuildFileNodeDescriptor)userObject;
      }
      node = (DefaultMutableTreeNode)node.getParent();
    }
    return null;
  }

  private void popupInvoked(final Component comp, final int x, final int y) {
    Object userObject = null;
    final TreePath path = myTree.getSelectionPath();
    if (path != null) {
      final DefaultMutableTreeNode node = (DefaultMutableTreeNode)path.getLastPathComponent();
      if (node != null) {
        userObject = node.getUserObject();
      }
    }
    final DefaultActionGroup group = new DefaultActionGroup();
    group.add(new RunAction());
    group.add(new CreateMetaTargetAction());
    group.add(new RemoveMetaTargetsOrBuildFileAction());
    group.add(ActionManager.getInstance().getAction(IdeActions.ACTION_EDIT_SOURCE));
    group.add(new AntGroupManagerActionGroup(null, myTree));
    group.add(new RemoveGroupsAction(myTree));
    if (userObject instanceof AntBuildFileNodeDescriptor) {
      group.add(new RemoveBuildFileAction(this));
    }
    if (userObject instanceof AntTargetNodeDescriptor) {
      final AntBuildTargetBase target = ((AntTargetNodeDescriptor)userObject).getTarget();
      final DefaultActionGroup executeOnGroup =
        new DefaultActionGroup(AntBundle.message("ant.explorer.execute.on.action.group.name"), true);
      executeOnGroup.add(new ExecuteOnEventAction(target, ExecuteBeforeCompilationEvent.getInstance()));
      executeOnGroup.add(new ExecuteOnEventAction(target, ExecuteAfterCompilationEvent.getInstance()));
      executeOnGroup.addSeparator();
      executeOnGroup.add(new ExecuteBeforeRunAction(target));
      group.add(executeOnGroup);
      group.add(new AssignShortcutAction(target.getActionId()));
    }
    group.add(myAntBuildFilePropertiesAction);
    final ActionPopupMenu popupMenu = ActionManager.getInstance().createActionPopupMenu(ActionPlaces.ANT_EXPLORER_POPUP, group);
    popupMenu.getComponent().show(comp, x, y);
  }

  @Override
  public void uiDataSnapshot(DataSink sink) {
    super.uiDataSnapshot(sink);
    sink.set(PlatformDataKeys.HELP_ID, HelpID.ANT);
    sink.set(PlatformDataKeys.TREE_EXPANDER, myProject != null ? myTreeExpander : null);

    final Tree tree = myTree;
    if (tree == null) {
      return;
    }
    final TreePath[] paths = tree.getSelectionPaths();
    final TreePath leadPath = tree.getLeadSelectionPath();
    final AntBuildFile currentBuildFile = getCurrentBuildFile();
    sink.lazy(PlatformDataKeys.VIRTUAL_FILE_ARRAY, () -> {
      final List<VirtualFile> virtualFiles = collectAntFiles(buildFile -> {
        final VirtualFile virtualFile = buildFile.getVirtualFile();
        if (virtualFile != null && virtualFile.isValid()) {
          return virtualFile;
        }
        return null;
      }, paths);
      return virtualFiles == null ? null : virtualFiles.toArray(VirtualFile.EMPTY_ARRAY);
    });
    sink.lazy(LangDataKeys.PSI_ELEMENT_ARRAY, () -> {
      final List<PsiElement> elements = collectAntFiles(AntBuildFile::getAntFile, paths);
      return elements == null ? null : elements.toArray(PsiElement.EMPTY_ARRAY);
    });
    sink.lazy(PlatformDataKeys.NAVIGATABLE, () -> {
      if (leadPath != null) {
        final DefaultMutableTreeNode node = (DefaultMutableTreeNode)leadPath.getLastPathComponent();
        if (node != null) {
          if (node.getUserObject() instanceof AntTargetNodeDescriptor targetNodeDescriptor) {
            final Navigatable navigatable = targetNodeDescriptor.getTarget().getOpenFileDescriptor();
            if (navigatable != null && navigatable.canNavigate()) {
              return navigatable;
            }
          }
        }
      }
      if (currentBuildFile != null && myProject != null && !myProject.isDisposed()) {
        final VirtualFile file = currentBuildFile.getVirtualFile();
        if (file != null && file.isValid()) {
          return OpenFileDescriptorFactory.getInstance(myProject).builder(file).build();
        }
      }
      return null;
    });
  }

  private static <T> List<T> collectAntFiles(final Function<? super AntBuildFile, ? extends T> function, final TreePath[] paths) {
    if (paths == null || paths.length == 0) {
      return null;
    }
    final Set<AntBuildFile> antFiles = new LinkedHashSet<>();
    for (final TreePath path : paths) {
      for (DefaultMutableTreeNode node = (DefaultMutableTreeNode)path.getLastPathComponent();
           node != null;
           node = (DefaultMutableTreeNode)node.getParent()) {
        final Object userObject = node.getUserObject();
        if (!(userObject instanceof AntBuildFileNodeDescriptor)) {
          continue;
        }
        final AntBuildFile buildFile = ((AntBuildFileNodeDescriptor)userObject).getBuildFile();
        if (buildFile != null) {
          antFiles.add(buildFile);
        }
        break;
      }
    }
    final List<T> result = new ArrayList<>();
    for (final AntBuildFile buildFile : antFiles) {
      ContainerUtil.addIfNotNull(result, function.apply(buildFile));
    }
    return result.isEmpty() ? null : result;
  }

  public static FileChooserDescriptor createXmlDescriptor() {
    return new FileChooserDescriptor(true, false, false, false, false, true) {
      public boolean isFileVisible(consulo.virtualFileSystem.VirtualFile file, boolean showHiddenFiles) {
        boolean b = super.isFileVisible(file, showHiddenFiles);
        if (!file.isDirectory()) {
          b &= XmlFileType.INSTANCE.equals(file.getFileType());
        }
        return b;
      }
    };
  }

  private static final class NodeRenderer extends ColoredTreeCellRenderer {
    public void customizeCellRenderer(JTree tree,
                                      Object value,
                                      boolean selected,
                                      boolean expanded,
                                      boolean leaf,
                                      int row,
                                      boolean hasFocus) {
      final Object userObject = ((DefaultMutableTreeNode)value).getUserObject();
      if (userObject instanceof AntNodeDescriptor) {
        final AntNodeDescriptor descriptor = (AntNodeDescriptor)userObject;
        descriptor.customize(this);
      }
      else {
        append(tree.convertValueToText(value, selected, expanded, leaf, row, hasFocus), SimpleTextAttributes.REGULAR_ATTRIBUTES);
      }
    }
  }

  private final class AddAction extends AnAction {
    public AddAction() {
      super(AntBundle.message("add.ant.file.action.name"),
            AntBundle.message("add.ant.file.action.description"),
            PlatformIconGroup.generalAdd());
    }

    public void actionPerformed(AnActionEvent e) {
      addBuildFile();
    }
  }

  private final class RemoveAction extends AnAction {
    public RemoveAction() {
      super(AntBundle.message("remove.ant.file.action.name"),
            AntBundle.message("remove.ant.file.action.description"),
            PlatformIconGroup.generalRemove());
    }

    public void actionPerformed(AnActionEvent e) {
      removeBuildFile();
    }

    public void update(AnActionEvent event) {
      event.getPresentation().setEnabled(getCurrentBuildFile() != null);
    }
  }

  private final class RunAction extends AnAction {
    public RunAction() {
      super(AntBundle.message("run.ant.file.or.target.action.name"), AntBundle.message("run.ant.file.or.target.action.description"),
            AllIcons.Actions.Execute);
    }

    public void actionPerformed(AnActionEvent e) {
      runSelection(e.getDataContext());
    }

    public void update(AnActionEvent event) {
      final Presentation presentation = event.getPresentation();
      final String place = event.getPlace();
      if (ActionPlaces.ANT_EXPLORER_TOOLBAR.equals(place)) {
        presentation.setText(AntBundle.message("run.ant.file.or.target.action.name"));
      }
      else {
        final TreePath[] paths = myTree.getSelectionPaths();
        if (paths != null && paths.length == 1 &&
          ((DefaultMutableTreeNode)paths[0].getLastPathComponent()).getUserObject() instanceof AntBuildFileNodeDescriptor) {
          presentation.setText(AntBundle.message("run.ant.build.action.name"));
        }
        else {
          if (paths == null || paths.length == 1) {
            presentation.setText(AntBundle.message("run.ant.target.action.name"));
          }
          else {
            presentation.setText(AntBundle.message("run.ant.targets.action.name"));
          }
        }
      }

      presentation.setEnabled(canRunSelection());
    }
  }

  private final class ShowAllTargetsAction extends ToggleAction {
    public ShowAllTargetsAction() {
      super(AntBundle.message("filter.ant.targets.action.name"), AntBundle.message("filter.ant.targets.action.description"),
            AllIcons.General.Filter);
    }

    public boolean isSelected(AnActionEvent event) {
      final Project project = myProject;
      return project != null && AntConfigurationBase.getInstance(project).isFilterTargets();
    }

    public void setSelected(AnActionEvent event, boolean flag) {
      setTargetsFiltered(flag);
    }
  }

  private final class ShowModuleGrouping extends ToggleAction {
    public ShowModuleGrouping() {
      super("Module grouping", "Module grouping", AllIcons.Actions.GroupByModule);
    }

    public boolean isSelected(AnActionEvent event) {
      final Project project = myProject;
      return project != null && AntConfigurationBase.getInstance(project).isModuleGrouping();
    }

    public void setSelected(AnActionEvent event, boolean flag) {
      myBuilder.setModuleGrouping(flag);
      AntConfigurationBase.getInstance(myProject).setModuleGrouping(flag);
    }
  }

  private void setTargetsFiltered(boolean value) {
    myBuilder.setTargetsFiltered(value);
    AntConfigurationBase.getInstance(myProject).setFilterTargets(value);
  }

  private final class ExecuteOnEventAction extends ToggleAction {
    private final AntBuildTargetBase myTarget;
    private final ExecutionEvent myExecutionEvent;

    public ExecuteOnEventAction(final AntBuildTargetBase target, final ExecutionEvent executionEvent) {
      super(executionEvent.getPresentableName());
      myTarget = target;
      myExecutionEvent = executionEvent;
    }

    public boolean isSelected(AnActionEvent e) {
      return myTarget.equals(AntConfigurationBase.getInstance(myProject).getTargetForEvent(myExecutionEvent));
    }

    public void setSelected(AnActionEvent event, boolean state) {
      final AntConfigurationBase antConfiguration = AntConfigurationBase.getInstance(myProject);
      if (state) {
        final AntBuildFileBase buildFile =
          (AntBuildFileBase)((myTarget instanceof MetaTarget) ? ((MetaTarget)myTarget).getBuildFile() : myTarget.getModel().getBuildFile());
        antConfiguration.setTargetForEvent(buildFile, myTarget.getName(), myExecutionEvent);
      }
      else {
        antConfiguration.clearTargetForEvent(myExecutionEvent);
      }
      myBuilder.queueUpdate();
    }

    public void update(AnActionEvent e) {
      super.update(e);
      final AntBuildFile buildFile = myTarget.getModel().getBuildFile();
      e.getPresentation().setEnabled(buildFile != null && buildFile.exists());
    }
  }

  private final class ExecuteBeforeRunAction extends AnAction {
    private final AntBuildTarget myTarget;

    public ExecuteBeforeRunAction(final AntBuildTarget target) {
      super(AntBundle.message("executes.before.run.debug.acton.name"));
      myTarget = target;
    }

    public void actionPerformed(AnActionEvent e) {
      final AntExecuteBeforeRunDialog dialog = new AntExecuteBeforeRunDialog(myProject, myTarget);
      dialog.show();
    }

    public void update(AnActionEvent e) {
      e.getPresentation().setEnabled(myTarget.getModel().getBuildFile().exists());
    }
  }

  private final class CreateMetaTargetAction extends AnAction {

    public CreateMetaTargetAction() {
      super(AntBundle.message("ant.create.meta.target.action.name"), AntBundle.message("ant.create.meta.target.action.description"), null
        /*IconLoader.getIcon("/actions/execute.png")*/);
    }

    public void actionPerformed(AnActionEvent e) {
      final AntBuildFile buildFile = getCurrentBuildFile();
      final String[] targets = getTargetNamesFromPaths(myTree.getSelectionPaths());
      final ExecuteCompositeTargetEvent event = new ExecuteCompositeTargetEvent(targets);
      final SaveMetaTargetDialog dialog = new SaveMetaTargetDialog(myTree, event, AntConfigurationBase.getInstance(myProject), buildFile);
      dialog.setTitle(e.getPresentation().getText());
      dialog.show();
      if (dialog.isOK()) {
        myBuilder.queueUpdate();
        myTree.repaint();
      }
    }

    public void update(AnActionEvent e) {
      final TreePath[] paths = myTree.getSelectionPaths();
      e.getPresentation().setEnabled(paths != null && paths.length > 1 && canRunSelection());
    }
  }

  private final class RemoveMetaTargetsOrBuildFileAction extends AnAction {

    public RemoveMetaTargetsOrBuildFileAction() {
      super(AntBundle.message("remove.meta.targets.action.name"), AntBundle.message("remove.meta.targets.action.description"), null);
      registerCustomShortcutSet(new CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0)), myTree);
      Disposer.register(AntExplorer.this, () -> RemoveMetaTargetsOrBuildFileAction.this.unregisterCustomShortcutSet(myTree));
      myTree.registerKeyboardAction(new AbstractAction() {
        public void actionPerformed(ActionEvent e) {
          doAction();
        }
      }, KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
    }

    public void actionPerformed(AnActionEvent e) {
      doAction();
    }

    private void doAction() {
      final TreePath[] paths = myTree.getSelectionPaths();
      if (paths == null) {
        return;
      }
      try {
        // try to remove build file
        if (paths.length == 1) {
          final DefaultMutableTreeNode node = (DefaultMutableTreeNode)paths[0].getLastPathComponent();
          if (node.getUserObject() instanceof AntBuildFileNodeDescriptor) {
            final AntBuildFileNodeDescriptor descriptor = (AntBuildFileNodeDescriptor)node.getUserObject();
            if (descriptor.getBuildFile().equals(getCurrentBuildFile())) {
              removeBuildFile();
              return;
            }
          }
        }
        // try to remove meta targets
        final AntBuildTarget[] targets = getTargetObjectsFromPaths(paths);
        final AntConfigurationBase antConfiguration = AntConfigurationBase.getInstance(myProject);
        for (final AntBuildTarget buildTarget : targets) {
          if (buildTarget instanceof MetaTarget) {
            for (final ExecutionEvent event : antConfiguration.getEventsForTarget(buildTarget)) {
              if (event instanceof ExecuteCompositeTargetEvent) {
                antConfiguration.clearTargetForEvent(event);
              }
            }
          }
        }
      }
      finally {
        myBuilder.queueUpdate();
        myTree.repaint();
      }
    }

    public void update(AnActionEvent e) {
      final Presentation presentation = e.getPresentation();
      final TreePath[] paths = myTree.getSelectionPaths();
      if (paths == null) {
        presentation.setEnabled(false);
        return;
      }

      if (paths.length == 1) {
        String text = AntBundle.message("remove.meta.target.action.name");
        boolean enabled = false;
        final DefaultMutableTreeNode node = (DefaultMutableTreeNode)paths[0].getLastPathComponent();
        if (node.getUserObject() instanceof AntBuildFileNodeDescriptor) {
          final AntBuildFileNodeDescriptor descriptor = (AntBuildFileNodeDescriptor)node.getUserObject();
          if (descriptor.getBuildFile().equals(getCurrentBuildFile())) {
            text = AntBundle.message("remove.selected.build.file.action.name");
            enabled = true;
          }
        }
        else {
          if (node.getUserObject() instanceof AntTargetNodeDescriptor) {
            final AntTargetNodeDescriptor descr = (AntTargetNodeDescriptor)node.getUserObject();
            final AntBuildTargetBase target = descr.getTarget();
            if (target instanceof MetaTarget) {
              enabled = true;
            }
          }
        }
        presentation.setText(text);
        presentation.setEnabled(enabled);
      }
      else {
        presentation.setText(AntBundle.message("remove.selected.meta.targets.action.name"));
        final AntBuildTarget[] targets = getTargetObjectsFromPaths(paths);
        boolean enabled = targets.length > 0;
        for (final AntBuildTarget buildTarget : targets) {
          if (!(buildTarget instanceof MetaTarget)) {
            enabled = false;
            break;
          }
        }
        presentation.setEnabled(enabled);
      }
    }
  }

  private final class AssignShortcutAction extends AnAction {
    private final String myActionId;

    public AssignShortcutAction(String actionId) {
      super(AntBundle.message("ant.explorer.assign.shortcut.action.name"));
      myActionId = actionId;
    }

    public void actionPerformed(AnActionEvent e) {
      new consulo.ide.impl.idea.openapi.keymap.impl.ui.EditKeymapsDialog(myProject, myActionId).show();
    }

    public void update(AnActionEvent e) {
      e.getPresentation().setEnabled(myActionId != null && ActionManager.getInstance().getAction(myActionId) != null);
    }
  }

  private class KeymapListener implements KeymapManagerListener, Keymap.Listener {
    private Keymap myCurrentKeymap = null;

    public KeymapListener() {
      final KeymapManager keymapManager = KeymapManager.getInstance();
      final Keymap activeKeymap = keymapManager.getActiveKeymap();
      listenTo(activeKeymap);
      keymapManager.addKeymapManagerListener(this);
    }

    public void activeKeymapChanged(Keymap keymap) {
      listenTo(keymap);
      updateTree();
    }

    private void listenTo(Keymap keymap) {
      if (myCurrentKeymap != null) {
        myCurrentKeymap.removeShortcutChangeListener(this);
      }
      myCurrentKeymap = keymap;
      if (myCurrentKeymap != null) {
        myCurrentKeymap.addShortcutChangeListener(this);
      }
    }

    private void updateTree() {
      myBuilder.updateFromRoot();
    }

    public void onShortcutChanged(String actionId) {
      updateTree();
    }

    public void stopListen() {
      listenTo(null);
      KeymapManager.getInstance().removeKeymapManagerListener(this);
    }
  }

  private final class MyTransferHandler extends TransferHandler {

    @Override
    public boolean importData(final TransferSupport support) {
      if (canImport(support)) {
        addBuildFile(getAntFiles(support));
        return true;
      }
      return false;
    }

    @Override
    public boolean canImport(final TransferSupport support) {
      return FileCopyPasteUtil.isFileListFlavorAvailable(support.getDataFlavors());
    }

    private VirtualFile[] getAntFiles(final TransferSupport support) {
      List<VirtualFile> virtualFileList = new ArrayList<VirtualFile>();
      final List<File> fileList = FileCopyPasteUtil.getFileList(support.getTransferable());
      if (fileList != null) {
        for (File file : fileList) {
          ContainerUtil.addIfNotNull(virtualFileList, VirtualFileUtil.findFileByIoFile(file, true));
        }
      }

      return VirtualFileUtil.toVirtualFileArray(virtualFileList);
    }
  }
}
