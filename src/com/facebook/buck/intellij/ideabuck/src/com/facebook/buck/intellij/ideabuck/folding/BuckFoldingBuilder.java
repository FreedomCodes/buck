/*
 * Copyright 2017-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.intellij.ideabuck.folding;

import static com.google.common.base.Strings.isNullOrEmpty;

import com.facebook.buck.intellij.ideabuck.lang.psi.BuckTypes;
import com.facebook.buck.intellij.ideabuck.lang.psi.impl.BuckExpressionImpl;
import com.facebook.buck.intellij.ideabuck.lang.psi.impl.BuckPropertyLvalueImpl;
import com.facebook.buck.intellij.ideabuck.lang.psi.impl.BuckRuleBlockImpl;
import com.facebook.buck.intellij.ideabuck.lang.psi.impl.BuckRuleNameImpl;
import com.facebook.buck.intellij.ideabuck.lang.psi.impl.BuckValueArrayImpl;
import com.intellij.lang.ASTNode;
import com.intellij.lang.folding.FoldingBuilderEx;
import com.intellij.lang.folding.FoldingDescriptor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.impl.source.tree.CompositeElement;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Folds rules and arrays */
public class BuckFoldingBuilder extends FoldingBuilderEx {

  /** We fold large arrays by default; this constant defines "large" */
  private static final int DEFAULT_FOLDING_SIZE = 6;

  private final TokenSet arrayElements = TokenSet.create(BuckTypes.ARRAY_ELEMENTS);
  private final TokenSet values = TokenSet.create(BuckTypes.VALUE);

  @NotNull
  @Override
  public FoldingDescriptor[] buildFoldRegions(
      @NotNull PsiElement root, @NotNull Document document, boolean quick) {
    List<FoldingDescriptor> descriptors = new ArrayList<>();

    PsiTreeUtil.findChildrenOfAnyType(root, BuckRuleBlockImpl.class, BuckValueArrayImpl.class)
        .stream()
        .forEach(
            element -> {
              int offset = element instanceof BuckRuleBlockImpl ? 0 : 1;
              descriptors.add(
                  new FoldingDescriptor(
                      element.getNode(),
                      new TextRange(
                          element.getTextRange().getStartOffset() + offset,
                          element.getTextRange().getEndOffset() - offset)));
            });

    return descriptors.toArray(new FoldingDescriptor[descriptors.size()]);
  }

  @Nullable
  @Override
  public String getPlaceholderText(@NotNull ASTNode astNode) {
    if (!(astNode instanceof CompositeElement)) {
      return null;
    }

    CompositeElement compositeElement = (CompositeElement) astNode;
    IElementType type = compositeElement.getElementType();

    if (type.equals(BuckTypes.VALUE_ARRAY)) {
      return getArrayPlaceholderText(compositeElement);
    } else if (type.equals(BuckTypes.RULE_BLOCK)) {
      return getRulePlaceholderText(compositeElement);
    } else {
      return null;
    }
  }

  private String getArrayPlaceholderText(CompositeElement compositeElement) {
    int size = countValues(compositeElement);
    // Return null (the default value) if countValues() returns an error code
    return size < 0 ? null : Integer.toString(size);
  }

  private String getRulePlaceholderText(CompositeElement compositeElement) {
    PsiElement psiElement = compositeElement.getPsi();
    BuckRuleNameImpl buckRuleName = PsiTreeUtil.findChildOfType(psiElement, BuckRuleNameImpl.class);
    if (buckRuleName == null) {
      return null;
    }

    String name = null;
    Collection<BuckPropertyLvalueImpl> lvalues =
        PsiTreeUtil.findChildrenOfType(psiElement, BuckPropertyLvalueImpl.class);
    for (BuckPropertyLvalueImpl lvalue : lvalues) {
      if (lvalue.getText().equals("name")) {
        PsiElement element = lvalue;
        do {
          element = element.getNextSibling();
        } while (!(element instanceof BuckExpressionImpl));
        name = element.getText();
        break;
      }
    }

    return String.format(isNullOrEmpty(name) ? "%s" : "%s(%s)", buckRuleName.getText(), name);
  }

  @Override
  public boolean isCollapsedByDefault(@NotNull ASTNode astNode) {
    if (!(astNode instanceof CompositeElement)) {
      return false;
    }
    CompositeElement compositeElement = (CompositeElement) astNode;
    IElementType type = compositeElement.getElementType();

    if (type.equals(BuckTypes.VALUE_ARRAY)) {
      return getArrayIsCollapsedByDefault(compositeElement);
    } else if (type.equals(BuckTypes.RULE_BLOCK)) {
      return getRuleIsCollapsedByDefault();
    } else {
      return false;
    }
  }

  private boolean getArrayIsCollapsedByDefault(CompositeElement compositeElement) {
    return countValues(compositeElement) >= DEFAULT_FOLDING_SIZE;
  }

  private boolean getRuleIsCollapsedByDefault() {
    return false;
  }

  private int countValues(CompositeElement compositeElement) {
    ASTNode[] children = compositeElement.getChildren(arrayElements);
    if (children == null || children.length != 1) {
      return -1;
    }
    CompositeElement element = (CompositeElement) children[0];
    return element.countChildren(values);
  }
}
