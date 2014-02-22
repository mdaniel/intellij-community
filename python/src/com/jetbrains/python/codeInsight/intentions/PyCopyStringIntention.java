/*
 * Copyright 2000-2014 JetBrains s.r.o.
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
package com.jetbrains.python.codeInsight.intentions;

import com.intellij.codeInsight.intention.impl.BaseIntentionAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import com.jetbrains.python.PyBundle;
import com.jetbrains.python.PyTokenTypes;
import com.jetbrains.python.psi.*;
import com.jetbrains.python.psi.impl.PyStringLiteralExpressionImpl;
import org.jetbrains.annotations.NotNull;

import java.awt.datatransfer.StringSelection;

/**
 * Intention to copy concatenated string to the clipboard.
 */
public class PyCopyStringIntention extends BaseIntentionAction {

  @NotNull
  public String getFamilyName() {
    return PyBundle.message("INTN.copy.quoted.string");
  }

  public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
    if (!(file instanceof PyFile)) {
      return false;
    }

    final PsiElement caretElement = file.findElementAt(editor.getCaretModel().getOffset());
    PyStringLiteralExpression stringLit = PsiTreeUtil.getParentOfType(caretElement, PyStringLiteralExpression.class);
    if (stringLit == null) {
      return false;
    }
    String stringText = stringLit.getText();
    int prefixLength = PyStringLiteralExpressionImpl.getPrefixLength(stringText);
    stringText = stringText.substring(prefixLength);

    if (stringText.isEmpty()) {
      return false;
    }
    setText(PyBundle.message("INTN.copy.quoted.string"));
    return true;
  }

  public void invoke(@NotNull Project project, Editor editor, PsiFile file) throws IncorrectOperationException {
    final PsiElement elementAt = file.findElementAt(editor.getCaretModel().getOffset());
    PyStringLiteralExpression stringLit = PsiTreeUtil.getParentOfType(elementAt, PyStringLiteralExpression.class);
    if (stringLit == null) {
      return;
    }
    PyBinaryExpression binaryEx = PsiTreeUtil.getParentOfType(stringLit, PyBinaryExpression.class);
    String value = null;
    if (null != binaryEx) {
      final PyElementType op = binaryEx.getOperator();
      if (PyTokenTypes.PLUS.equals(op)) {
        final PyExpression left = binaryEx.getLeftExpression();
        final PyExpression right = binaryEx.getRightExpression();
        if (left instanceof PyStringLiteralExpression &&
            right instanceof PyStringLiteralExpression) {
          value = ((PyStringLiteralExpression)left).getStringValue() +
                  ((PyStringLiteralExpression)right).getStringValue();
        }
      }
    }
    if (null == value) {
      value = stringLit.getStringValue();
    }
    CopyPasteManager.getInstance().setContents(new StringSelection(value));
  }
}
