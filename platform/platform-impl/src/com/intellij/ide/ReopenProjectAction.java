// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ide;

import com.intellij.ide.impl.OpenProjectTask;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.util.BitUtil;
import com.intellij.util.PathUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.SystemIndependent;

import java.awt.event.InputEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
* @author yole
*/
@SuppressWarnings("ComponentNotRegistered")
public class ReopenProjectAction extends AnAction implements DumbAware {
  private final String myProjectPath;
  private final String myProjectName;
  private boolean myIsRemoved = false;

  public ReopenProjectAction(@NotNull @SystemIndependent String projectPath, String projectName, String displayName) {
    myProjectPath = projectPath;
    myProjectName = projectName;

    Presentation presentation = getTemplatePresentation();
    String text = projectPath.equals(displayName) ? FileUtil.getLocationRelativeToUserHome(projectPath) : displayName;
    presentation.setText(text, false);
    presentation.setDescription(PathUtil.toSystemDependentName(projectPath));
  }

  @Override
  public boolean startInTransaction() {
    return true;
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    // force move focus to IdeFrame
    IdeEventQueue.getInstance().getPopupManager().closeAllPopups();

    Project project = e.getProject();
    Path file = Paths.get(myProjectPath);
    if (!Files.exists(file)) {
      if (Messages.showDialog(project, "The path " + PathUtil.toSystemDependentName(myProjectPath) + " does not exist.\n" +
                                       "If it is on a removable or network drive, please make sure that the drive is connected.",
                                       "Reopen Project", new String[]{"OK", "&Remove From List"}, 0, Messages.getErrorIcon()) == 1) {
        myIsRemoved = true;
        RecentProjectsManager.getInstance().removePath(myProjectPath);
      }
      return;
    }


    int modifiers = e.getModifiers();
    boolean forceOpenInNewFrame = BitUtil.isSet(modifiers, InputEvent.CTRL_MASK)
                                  || BitUtil.isSet(modifiers, InputEvent.SHIFT_MASK)
                                  || e.getPlace() == ActionPlaces.WELCOME_SCREEN;
    RecentProjectsManagerBase.getInstanceEx().doOpenProject(file, new OpenProjectTask(forceOpenInNewFrame, project));
  }

  @SystemIndependent
  public String getProjectPath() {
    return myProjectPath;
  }

  public boolean isRemoved() {
    return myIsRemoved;
  }

  public String getProjectName() {
    final RecentProjectsManager mgr = RecentProjectsManager.getInstance();
    if (mgr instanceof RecentProjectsManagerBase) {
      return ((RecentProjectsManagerBase)mgr).getProjectName(myProjectPath);
    }
    return myProjectName;
  }

  @Nullable
  @Override
  public String getTemplateText() {
    return "Reopen Project";
  }
}
