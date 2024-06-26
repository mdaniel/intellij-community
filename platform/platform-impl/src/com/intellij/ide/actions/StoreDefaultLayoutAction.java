// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.actions;

import com.intellij.idea.ActionsBundle;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.toolWindow.ToolWindowDefaultLayoutManager;
import org.jetbrains.annotations.NotNull;

public final class StoreDefaultLayoutAction extends StoreNamedLayoutAction {

  public StoreDefaultLayoutAction() {
    super(() -> ToolWindowDefaultLayoutManager.getInstance().getActiveLayoutName());
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    super.update(e);
    String layoutName = getLayoutNameSupplier().invoke();
    if (ActionPlaces.MAIN_MENU.equals(e.getPlace())) {
      // In the main menu the main action is hidden, instead a child to the current layout submenu is added.
      e.getPresentation().setVisible(false);
    }
    e.getPresentation().setEnabled(!ToolWindowDefaultLayoutManager.FACTORY_DEFAULT_LAYOUT_NAME.equals(layoutName));
    e.getPresentation().setDescription(ActionsBundle.message("action.StoreDefaultLayout.named.description", layoutName));
  }

}
