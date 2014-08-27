/*
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

package com.google.devtools.j2objc.translate;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.devtools.j2objc.ast.Assignment;
import com.google.devtools.j2objc.ast.Expression;
import com.google.devtools.j2objc.ast.InfixExpression;
import com.google.devtools.j2objc.ast.MethodDeclaration;
import com.google.devtools.j2objc.ast.MethodInvocation;
import com.google.devtools.j2objc.ast.ParenthesizedExpression;
import com.google.devtools.j2objc.ast.SimpleName;
import com.google.devtools.j2objc.ast.Statement;
import com.google.devtools.j2objc.ast.TreeNode;
import com.google.devtools.j2objc.ast.TreeUtil;
import com.google.devtools.j2objc.ast.TreeVisitor;
import com.google.devtools.j2objc.ast.VariableDeclarationStatement;
import com.google.devtools.j2objc.types.GeneratedVariableBinding;
import com.google.devtools.j2objc.types.Types;

import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Detects deep expression trees and extracts them into separate statements.
 *
 * @author Keith Stanger
 */
public class ComplexExpressionExtractor extends TreeVisitor {

  // The ObjC compiler tends to fail with roughly 100 chained method calls.
  private static final int DEFAULT_MAX_DEPTH = 50;

  private static int maxDepth = DEFAULT_MAX_DEPTH;
  private Map<Expression, Integer> depths = Maps.newHashMap();
  private IMethodBinding currentMethod;
  private Statement currentStatement;
  private int count = 1;

  @VisibleForTesting
  static void setMaxDepth(int newMaxDepth) {
    maxDepth = newMaxDepth;
  }

  @VisibleForTesting
  static void resetMaxDepth() {
    maxDepth = DEFAULT_MAX_DEPTH;
  }

  private void handleNode(Expression node, Collection<Expression> children) {
    if (node.getParent() instanceof Statement) {
      return;
    }
    int depth = 0;
    for (Expression child : children) {
      Integer childDepth = depths.get(child);
      depth = Math.max(depth, childDepth != null ? childDepth : 1);
    }
    if (depth >= maxDepth) {
      ITypeBinding type = node.getTypeBinding();
      assert currentMethod != null; // Should be OK if run after InitializationNormalizer.
      IVariableBinding newVar = new GeneratedVariableBinding(
          "complex$" + count++, 0, type, false, false, null, currentMethod);
      Statement newStmt = new VariableDeclarationStatement(newVar, node.copy());
      assert currentStatement != null;
      TreeUtil.insertBefore(currentStatement, newStmt);
      node.replaceWith(new SimpleName(newVar));
    } else {
      depths.put(node, depth + 1);
    }
  }

  @Override
  public boolean preVisit(TreeNode node) {
    super.preVisit(node);
    if (node instanceof Statement) {
      currentStatement = (Statement) node;
    }
    return true;
  }

  @Override
  public boolean visit(MethodDeclaration node) {
    currentMethod = node.getMethodBinding();
    return true;
  }

  @Override
  public void endVisit(MethodDeclaration node) {
    currentMethod = null;
  }

  @Override
  public void endVisit(InfixExpression node) {
    handleNode(node, ImmutableList.of(node.getLeftOperand(), node.getRightOperand()));
  }

  @Override
  public void endVisit(MethodInvocation node) {
    Expression receiver = node.getExpression();
    List<Expression> args = node.getArguments();
    List<Expression> children = Lists.newArrayListWithCapacity(args.size() + 1);
    if (receiver != null) {
      children.add(receiver);
    }
    children.addAll(args);
    handleNode(node, children);
  }

  @Override
  public void endVisit(Assignment node) {
    if (Types.isBooleanType(node.getTypeBinding())
        && node.getRightHandSide() instanceof InfixExpression) {
      // Avoid clang precedence warning by putting parentheses around expression.
      Expression expr = node.getRightHandSide();
      ParenthesizedExpression newExpr = ParenthesizedExpression.parenthesize(expr.copy());
      expr.replaceWith(newExpr);
    }
  }
}
