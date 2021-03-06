// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.vcs.changes

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.AnActionExtensionProvider
import com.intellij.openapi.vcs.changes.EditorTabDiffPreviewManager.Companion.EDITOR_TAB_DIFF_PREVIEW

open class ShowEditorDiffPreviewActionProvider : AnActionExtensionProvider {
  override fun isActive(e: AnActionEvent): Boolean {
    val project = e.project

    return project != null &&
           getDiffPreview(e) != null &&
           EditorTabDiffPreviewManager.getInstance(project).isEditorDiffPreviewAvailable()
  }

  override fun update(e: AnActionEvent) {
    getDiffPreview(e)?.run { updateAvailability(e) }
  }

  override fun actionPerformed(e: AnActionEvent) {
    val diffPreview = getDiffPreview(e)!!

    val previewManager = EditorTabDiffPreviewManager.getInstance(e.project!!)
    previewManager.showDiffPreview(diffPreview)
  }

  open fun getDiffPreview(e: AnActionEvent) = e.getData(EDITOR_TAB_DIFF_PREVIEW)
}
