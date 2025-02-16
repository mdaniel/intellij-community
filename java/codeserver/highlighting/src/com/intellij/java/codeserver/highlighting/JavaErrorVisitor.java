// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.java.codeserver.highlighting;

import com.intellij.codeInsight.UnhandledExceptions;
import com.intellij.java.codeserver.highlighting.errors.JavaCompilationError;
import com.intellij.java.codeserver.highlighting.errors.JavaErrorKinds;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.JavaSdkVersion;
import com.intellij.openapi.projectRoots.JavaVersionService;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.pom.java.JavaFeature;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.*;
import com.intellij.psi.impl.source.resolve.JavaResolveUtil;
import com.intellij.psi.impl.source.tree.java.PsiReferenceExpressionImpl;
import com.intellij.psi.infos.MethodCandidateInfo;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.util.MethodSignatureBackedByPsiMethod;
import com.intellij.psi.util.PsiTypesUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.util.PsiUtilCore;
import com.intellij.refactoring.util.RefactoringChangeUtil;
import com.intellij.util.ObjectUtils;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

/**
 * An internal visitor to gather error messages for Java sources. Should not be used directly.
 * Use {@link JavaErrorCollector} instead.
 */
final class JavaErrorVisitor extends JavaElementVisitor {
  private final @NotNull Consumer<JavaCompilationError<?, ?>> myErrorConsumer;
  private final @NotNull Project myProject;
  private final @NotNull PsiFile myFile;
  private final @NotNull LanguageLevel myLanguageLevel;
  private final @NotNull AnnotationChecker myAnnotationChecker = new AnnotationChecker(this);
  final @NotNull ClassChecker myClassChecker = new ClassChecker(this);
  private final @NotNull RecordChecker myRecordChecker = new RecordChecker(this);
  private final @NotNull GenericsChecker myGenericsChecker = new GenericsChecker(this);
  final @NotNull MethodChecker myMethodChecker = new MethodChecker(this);
  private final @NotNull ReceiverChecker myReceiverChecker = new ReceiverChecker(this);
  final @NotNull ExpressionChecker myExpressionChecker = new ExpressionChecker(this);
  private final @NotNull StatementChecker myStatementChecker = new StatementChecker(this);
  private final @NotNull LiteralChecker myLiteralChecker = new LiteralChecker(this);
  private final @Nullable PsiJavaModule myJavaModule;
  private final @NotNull JavaSdkVersion myJavaSdkVersion;
  private boolean myHasError; // true if myHolder.add() was called with HighlightInfo of >=ERROR severity. On each .visit(PsiElement) call this flag is reset. Useful to determine whether the error was already reported while visiting this PsiElement.

  JavaErrorVisitor(@NotNull PsiFile file, @Nullable PsiJavaModule module, @NotNull Consumer<JavaCompilationError<?, ?>> consumer) {
    myFile = file;
    myProject = file.getProject();
    myLanguageLevel = PsiUtil.getLanguageLevel(file);
    myErrorConsumer = consumer;
    myJavaModule = module;
    myJavaSdkVersion = ObjectUtils
      .notNull(JavaVersionService.getInstance().getJavaSdkVersion(file), JavaSdkVersion.fromLanguageLevel(myLanguageLevel));
  }

  void report(@NotNull JavaCompilationError<?, ?> error) {
    myErrorConsumer.accept(error);
    myHasError = true;
  }
  
  @Contract(pure = true)
  boolean isApplicable(@NotNull JavaFeature feature) {
    return feature.isSufficient(myLanguageLevel);
  }

  @NotNull PsiFile file() {
    return myFile;
  }

  @NotNull Project project() {
    return myProject;
  }

  @NotNull LanguageLevel languageLevel() {
    return myLanguageLevel;
  }

  @NotNull JavaSdkVersion sdkVersion() {
    return myJavaSdkVersion;
  }

  @Contract(pure = true)
  boolean hasErrorResults() {
    return myHasError;
  }

  @Override
  public void visitElement(@NotNull PsiElement element) {
    super.visitElement(element);
    myHasError = false;
  }

  @Override
  public void visitRecordComponent(@NotNull PsiRecordComponent recordComponent) {
    super.visitRecordComponent(recordComponent);
    if (!hasErrorResults()) myRecordChecker.checkRecordComponentWellFormed(recordComponent);
    if (!hasErrorResults()) myRecordChecker.checkRecordAccessorReturnType(recordComponent);
  }

  @Override
  public void visitTryStatement(@NotNull PsiTryStatement statement) {
    super.visitTryStatement(statement);
    if (!hasErrorResults()) {
      UnhandledExceptions thrownTypes = collectUnhandledExceptions(statement);
      if (thrownTypes.hasUnresolvedCalls()) return;
      for (PsiParameter parameter : statement.getCatchBlockParameters()) {
        myStatementChecker.checkExceptionAlreadyCaught(parameter);
        if (!hasErrorResults()) myStatementChecker.checkExceptionThrownInTry(parameter, thrownTypes.exceptions());
      }
    }
  }
  
  @Override
  public void visitParameter(@NotNull PsiParameter parameter) {
    super.visitParameter(parameter);

    PsiElement parent = parameter.getParent();
    if (parent instanceof PsiParameterList && parameter.isVarArgs()) {
      if (!hasErrorResults()) checkFeature(parameter, JavaFeature.VARARGS);
      if (!hasErrorResults()) myMethodChecker.checkVarArgParameterWellFormed(parameter);
    }
    else if (parent instanceof PsiCatchSection) {
      if (!hasErrorResults() && parameter.getType() instanceof PsiDisjunctionType) {
        checkFeature(parameter, JavaFeature.MULTI_CATCH);
      }
      if (!hasErrorResults()) myStatementChecker.checkCatchTypeIsDisjoint(parameter);
    }
    else if (parent instanceof PsiForeachStatement forEach) {
      checkFeature(forEach, JavaFeature.FOR_EACH);
      if (!hasErrorResults()) myGenericsChecker.checkForEachParameterType(forEach, parameter);
    }
  }

  @Override
  public void visitAnnotation(@NotNull PsiAnnotation annotation) {
    super.visitAnnotation(annotation);
    if (!hasErrorResults()) checkFeature(annotation, JavaFeature.ANNOTATIONS);
    myAnnotationChecker.checkAnnotation(annotation);
  }
  
  @Override
  public void visitPackageStatement(@NotNull PsiPackageStatement statement) {
    super.visitPackageStatement(statement);
    if (!hasErrorResults()) myAnnotationChecker.checkPackageAnnotationContainingFile(statement);
    if (!hasErrorResults()) myClassChecker.checkPackageNotAllowedInImplicitClass(statement);
  }

  @Override
  public void visitReceiverParameter(@NotNull PsiReceiverParameter parameter) {
    super.visitReceiverParameter(parameter);
    myReceiverChecker.checkReceiver(parameter);
  }

  @Override
  public void visitNameValuePair(@NotNull PsiNameValuePair pair) {
    super.visitNameValuePair(pair);
    myAnnotationChecker.checkNameValuePair(pair);
  }

  @Override
  public void visitEnumConstant(@NotNull PsiEnumConstant enumConstant) {
    super.visitEnumConstant(enumConstant);
    if (!hasErrorResults()) myClassChecker.checkEnumWithAbstractMethods(enumConstant);
  }

  @Override
  public void visitNewExpression(@NotNull PsiNewExpression expression) {
    super.visitNewExpression(expression);
    PsiType type = expression.getType();
    PsiClass aClass = PsiUtil.resolveClassInType(type);
    if (aClass != null && !hasErrorResults()) myClassChecker.checkIllegalInstantiation(aClass, expression);
    if (aClass != null && !(type instanceof PsiArrayType) && !(type instanceof PsiPrimitiveType) && !hasErrorResults()) {
      myExpressionChecker.checkCreateInnerClassFromStaticContext(expression, aClass);
    }
    if (!hasErrorResults()) myClassChecker.checkAnonymousInheritFinal(expression);
    if (!hasErrorResults()) myClassChecker.checkAnonymousInheritProhibited(expression);
    if (!hasErrorResults()) myClassChecker.checkAnonymousSealedProhibited(expression);
    if (!hasErrorResults()) myExpressionChecker.checkQualifiedNew(expression, type, aClass);
  }

  @Override
  public void visitComment(@NotNull PsiComment comment) {
    super.visitComment(comment);
    if (!hasErrorResults()) checkShebangComment(comment);
    if (!hasErrorResults()) checkUnclosedComment(comment);
    if (!hasErrorResults()) checkIllegalUnicodeEscapes(comment);
  }

  @Override
  public void visitDocComment(@NotNull PsiDocComment comment) {
    if (!hasErrorResults()) checkUnclosedComment(comment);
    if (!hasErrorResults()) checkIllegalUnicodeEscapes(comment);
  }

  @Override
  public void visitFragment(@NotNull PsiFragment fragment) {
    super.visitFragment(fragment);
    checkIllegalUnicodeEscapes(fragment);
    if (!hasErrorResults()) myLiteralChecker.checkFragmentError(fragment);
  }

  @Override
  public void visitLiteralExpression(@NotNull PsiLiteralExpression expression) {
    super.visitLiteralExpression(expression);

    if (!hasErrorResults() &&
        expression.getParent() instanceof PsiCaseLabelElementList &&
        expression.textMatches(PsiKeyword.NULL)) {
      checkFeature(expression, JavaFeature.PATTERNS_IN_SWITCH);
    }

    if (!hasErrorResults()) checkIllegalUnicodeEscapes(expression);
    if (!hasErrorResults()) myLiteralChecker.getLiteralExpressionParsingError(expression);
  }

  @Override
  public void visitTypeParameterList(@NotNull PsiTypeParameterList list) {
    PsiTypeParameter[] typeParameters = list.getTypeParameters();
    if (typeParameters.length > 0) {
      checkFeature(list, JavaFeature.GENERICS);
      if (!hasErrorResults()) myGenericsChecker.checkTypeParametersList(list, typeParameters);
    }
  }

  @Override
  public void visitMethodReferenceExpression(@NotNull PsiMethodReferenceExpression expression) {
    visitElement(expression);
    checkFeature(expression, JavaFeature.METHOD_REFERENCES);
    PsiType functionalInterfaceType = expression.getFunctionalInterfaceType();
    if (functionalInterfaceType != null && !PsiTypesUtil.allTypeParametersResolved(expression, functionalInterfaceType)) return;

    JavaResolveResult result;
    JavaResolveResult[] results;
    try {
      results = expression.multiResolve(true);
      result = results.length == 1 ? results[0] : JavaResolveResult.EMPTY;
    }
    catch (IndexNotReadyException e) {
      return;
    }
    if (!hasErrorResults()) {
      boolean resolvedButNonApplicable = results.length == 1 && results[0] instanceof MethodCandidateInfo methodInfo &&
                                         !methodInfo.isApplicable() &&
                                         functionalInterfaceType != null;
      if (results.length != 1 || resolvedButNonApplicable) {
        if (expression.isConstructor()) {
          PsiClass containingClass = PsiMethodReferenceUtil.getQualifierResolveResult(expression).getContainingClass();

          if (containingClass != null) {
            myClassChecker.checkIllegalInstantiation(containingClass, expression);
          }
        }
      }
    }
  }

  @Override
  public void visitModifierList(@NotNull PsiModifierList list) {
    super.visitModifierList(list);
    PsiElement parent = list.getParent();
    if (parent instanceof PsiMethod method) {
      if (!hasErrorResults()) {
        myClassChecker.checkConstructorCallsBaseClassConstructor(method);
      }
      if (!hasErrorResults()) myMethodChecker.checkMethodCanHaveBody(method);
      if (!hasErrorResults()) myMethodChecker.checkMethodMustHaveBody(method);
      if (!hasErrorResults()) myMethodChecker.checkStaticMethodOverride(method);
      MethodSignatureBackedByPsiMethod methodSignature = MethodSignatureBackedByPsiMethod.create(method, PsiSubstitutor.EMPTY);
      PsiClass aClass = method.getContainingClass();
      if (!method.isConstructor()) {
        List<HierarchicalMethodSignature> superMethodSignatures = method.getHierarchicalMethodSignature().getSuperSignatures();
        if (!superMethodSignatures.isEmpty()) {
          if (!method.hasModifierProperty(PsiModifier.STATIC)) {
            if (!hasErrorResults()) myMethodChecker.checkMethodWeakerPrivileges(method, methodSignature, superMethodSignatures);
            if (!hasErrorResults()) myMethodChecker.checkMethodOverridesFinal(methodSignature, superMethodSignatures);
          }
          if (!hasErrorResults()) myMethodChecker.checkMethodIncompatibleReturnType(method, methodSignature, superMethodSignatures);
          if (aClass != null && !hasErrorResults()) {
            myMethodChecker.checkMethodIncompatibleThrows(method, methodSignature, superMethodSignatures, aClass);
          }
        }
      }
    }
    else if (parent instanceof PsiClass aClass) {
      if (!hasErrorResults()) myClassChecker.checkDuplicateNestedClass(aClass);
      if (!hasErrorResults() && !(aClass instanceof PsiAnonymousClass)) {
        /* anonymous class is highlighted in HighlightClassUtil.checkAbstractInstantiation()*/
        myClassChecker.checkClassMustBeAbstract(aClass);
      }
      if (!hasErrorResults()) {
        myClassChecker.checkClassDoesNotCallSuperConstructorOrHandleExceptions(aClass);
      }
      //if (!hasErrorResults()) add(HighlightMethodUtil.checkOverrideEquivalentInheritedMethods(aClass, myFile, myLanguageLevel));
      if (!hasErrorResults()) {
        //GenericsHighlightUtil.computeOverrideEquivalentMethodErrors(aClass, myOverrideEquivalentMethodsVisitedClasses, myOverrideEquivalentMethodsErrors);
        //myErrorSink.accept(myOverrideEquivalentMethodsErrors.get(aClass));
      }
      if (!hasErrorResults()) myClassChecker.checkCyclicInheritance(aClass);
      if (!hasErrorResults()) myMethodChecker.checkOverrideEquivalentInheritedMethods(aClass);
    }
  }

  @Override
  public void visitJavaFile(@NotNull PsiJavaFile file) {
    super.visitJavaFile(file);
    if (!hasErrorResults()) myClassChecker.checkImplicitClassWellFormed(file);
    if (!hasErrorResults()) myClassChecker.checkDuplicateClassesWithImplicit(file);
  }

  @Override
  public void visitClassInitializer(@NotNull PsiClassInitializer initializer) {
    super.visitClassInitializer(initializer);
    if (!hasErrorResults()) myClassChecker.checkImplicitClassMember(initializer);
    if (!hasErrorResults()) myClassChecker.checkIllegalInstanceMemberInRecord(initializer);
    if (!hasErrorResults()) myClassChecker.checkThingNotAllowedInInterface(initializer);
    if (!hasErrorResults()) myClassChecker.checkInitializersInImplicitClass(initializer);
  }

  @Override
  public void visitField(@NotNull PsiField field) {
    super.visitField(field);
    if (!hasErrorResults()) myClassChecker.checkIllegalInstanceMemberInRecord(field);
  }

  @Override
  public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
    if (!hasErrorResults()) myClassChecker.checkEnumSuperConstructorCall(expression);
    if (!hasErrorResults()) myClassChecker.checkSuperQualifierType(expression);
    if (!hasErrorResults()) myExpressionChecker.checkMethodCall(expression);
    if (!hasErrorResults()) visitExpression(expression);
  }

  @Override
  public void visitIdentifier(@NotNull PsiIdentifier identifier) {
    PsiElement parent = identifier.getParent();
    if (parent instanceof PsiVariable variable) {
      if (variable instanceof PsiField field) {
        myClassChecker.checkImplicitClassMember(field);
      }
    }
    else if (parent instanceof PsiClass aClass) {
      if (aClass.isAnnotationType()) {
        checkFeature(identifier, JavaFeature.ANNOTATIONS);
      }
      if (!hasErrorResults()) myClassChecker.checkClassAlreadyImported(aClass);
      if (!hasErrorResults()) myClassChecker.checkClassRestrictedKeyword(identifier);
    }
    else if (parent instanceof PsiMethod method) {
      myClassChecker.checkImplicitClassMember(method);
    }
  }

  @Override
  public void visitKeyword(@NotNull PsiKeyword keyword) {
    super.visitKeyword(keyword);
    if (!hasErrorResults()) myClassChecker.checkStaticDeclarationInInnerClass(keyword);
    if (!hasErrorResults()) myExpressionChecker.checkIllegalVoidType(keyword);
  }

  @Override
  public void visitLabeledStatement(@NotNull PsiLabeledStatement statement) {
    super.visitLabeledStatement(statement);
    if (!hasErrorResults()) myStatementChecker.checkLabelWithoutStatement(statement);
    if (!hasErrorResults()) myStatementChecker.checkLabelAlreadyInUse(statement);
  }

  @Override
  public void visitImportStaticStatement(@NotNull PsiImportStaticStatement statement) {
    checkFeature(statement, JavaFeature.STATIC_IMPORTS);
    if (!hasErrorResults()) {
      PsiJavaCodeReferenceElement importReference = statement.getImportReference();
      PsiClass targetClass = statement.resolveTargetClass();
      if (importReference != null) {
        PsiElement referenceNameElement = importReference.getReferenceNameElement();
        if (referenceNameElement != null && targetClass != null) {
          myGenericsChecker.checkClassSupersAccessibility(targetClass, referenceNameElement, myFile.getResolveScope());
        }
      }
    }
  }

  @Override
  public void visitClass(@NotNull PsiClass aClass) {
    super.visitClass(aClass);
    if (aClass instanceof PsiSyntheticClass) return;
    if (!hasErrorResults()) myClassChecker.checkDuplicateTopLevelClass(aClass);
    if (!hasErrorResults()) myClassChecker.checkMustNotBeLocal(aClass);
    if (!hasErrorResults()) myClassChecker.checkClassAndPackageConflict(aClass);
    if (!hasErrorResults()) myClassChecker.checkPublicClassInRightFile(aClass);
    if (!hasErrorResults()) myClassChecker.checkSealedClassInheritors(aClass);
    if (!hasErrorResults()) myClassChecker.checkSealedSuper(aClass);
    if (!hasErrorResults()) myClassChecker.checkImplicitThisReferenceBeforeSuper(aClass);
    if (!hasErrorResults()) myGenericsChecker.checkInterfaceMultipleInheritance(aClass);
    if (!hasErrorResults()) myGenericsChecker.checkClassSupersAccessibility(aClass);
    if (!hasErrorResults()) myRecordChecker.checkRecordHeader(aClass);
  }

  @Override
  public void visitReferenceExpression(@NotNull PsiReferenceExpression expression) {
    JavaResolveResult resultForIncompleteCode = doVisitReferenceElement(expression);
    if (!hasErrorResults()) {
      visitExpression(expression);
      if (hasErrorResults()) return;
    }
    JavaResolveResult[] results = resolveOptimised(expression);
    if (results == null) return;
    JavaResolveResult result = results.length == 1 ? results[0] : JavaResolveResult.EMPTY;

    PsiElement resolved = result.getElement();
    PsiElement parent = expression.getParent();
    PsiExpression qualifierExpression = expression.getQualifierExpression();
    if (!hasErrorResults() && myJavaModule == null && qualifierExpression != null) {
      if (parent instanceof PsiMethodCallExpression) {
        PsiClass psiClass = RefactoringChangeUtil.getQualifierClass(expression);
        if (psiClass != null) {
          myGenericsChecker.checkClassSupersAccessibility(psiClass, expression, myFile.getResolveScope());
        }
      }
    }
    if (!hasErrorResults() && resultForIncompleteCode != null && PsiUtil.isAvailable(JavaFeature.PATTERNS_IN_SWITCH, expression)) {
      myExpressionChecker.checkPatternVariableRequired(expression, resultForIncompleteCode);
    }
    if (!hasErrorResults() && resultForIncompleteCode != null) {
      myExpressionChecker.checkExpressionRequired(expression, resultForIncompleteCode);
    }
    if (!hasErrorResults() && resolved instanceof PsiField field) {
      myExpressionChecker.checkIllegalForwardReferenceToField(expression, field);
    }
  }
  
  

  @Override
  public void visitArrayInitializerExpression(@NotNull PsiArrayInitializerExpression expression) {
    super.visitArrayInitializerExpression(expression);
    if (!hasErrorResults()) myExpressionChecker.checkArrayInitializerApplicable(expression);
  }

    @Override
  public void visitReferenceElement(@NotNull PsiJavaCodeReferenceElement ref) {
    JavaResolveResult result = ref instanceof PsiExpression ? resolveOptimised(ref, myFile) : doVisitReferenceElement(ref);
    if (result != null) {
      PsiElement resolved = result.getElement();
      if (!hasErrorResults() && resolved instanceof PsiClass aClass) {
        myExpressionChecker.checkLocalClassReferencedFromAnotherSwitchBranch(ref, aClass);
      }
      if (!hasErrorResults()) myGenericsChecker.checkRawOnParameterizedType(ref, resolved);
    }
  }

  @Override
  public void visitSwitchLabelStatement(@NotNull PsiSwitchLabelStatement statement) {
    super.visitSwitchLabelStatement(statement);
    if (!hasErrorResults()) myStatementChecker.checkCaseStatement(statement);
    if (!hasErrorResults()) myStatementChecker.checkGuard(statement);
  }

  @Override
  public void visitSwitchLabeledRuleStatement(@NotNull PsiSwitchLabeledRuleStatement statement) {
    super.visitSwitchLabeledRuleStatement(statement);
    if (!hasErrorResults()) myStatementChecker.checkCaseStatement(statement);
    if (!hasErrorResults()) myStatementChecker.checkGuard(statement);
  }

  private JavaResolveResult doVisitReferenceElement(@NotNull PsiJavaCodeReferenceElement ref) {
    JavaResolveResult result = resolveOptimised(ref, myFile);
    if (result == null) return null;

    PsiElement resolved = result.getElement();
    PsiElement parent = ref.getParent();

    if (resolved != null && parent instanceof PsiReferenceList referenceList && !hasErrorResults()) {
      checkElementInReferenceList(ref, referenceList, result);
    }
    if (!hasErrorResults()) myClassChecker.checkAbstractInstantiation(ref);
    if (!hasErrorResults()) myClassChecker.checkExtendsDuplicate(ref, resolved);
    if (!hasErrorResults()) myClassChecker.checkClassExtendsForeignInnerClass(ref, resolved);
    if (!hasErrorResults() && parent instanceof PsiNewExpression newExpression) myGenericsChecker.checkDiamondTypeNotAllowed(newExpression);
    if (!hasErrorResults()) myGenericsChecker.checkSelectStaticClassFromParameterizedType(resolved, ref);
    return result;
  }

  private void checkShebangComment(@NotNull PsiComment comment) {
    if (comment.getTextOffset() != 0) return;
    if (comment.getText().startsWith("#!")) {
      VirtualFile file = PsiUtilCore.getVirtualFile(comment);
      if (file != null && "java".equals(file.getExtension())) {
        report(JavaErrorKinds.COMMENT_SHEBANG_JAVA_FILE.create(comment));
      }
    }
  }

  private void checkUnclosedComment(@NotNull PsiComment comment) {
    if (!(comment instanceof PsiDocComment) && comment.getTokenType() != JavaTokenType.C_STYLE_COMMENT) return;
    String text = comment.getText();
    if (text.startsWith("/*") && !text.endsWith("*/")) {
      report(JavaErrorKinds.COMMENT_UNCLOSED.create(comment));
    }
  }

  private void checkIllegalUnicodeEscapes(@NotNull PsiElement element) {
    LiteralChecker.parseUnicodeEscapes(element.getText(),
                                       (start, end) -> report(JavaErrorKinds.ILLEGAL_UNICODE_ESCAPE.create(element, TextRange.create(start, end))));
  }

  private void checkElementInReferenceList(@NotNull PsiJavaCodeReferenceElement ref,
                                           @NotNull PsiReferenceList referenceList,
                                           @NotNull JavaResolveResult resolveResult) {
    PsiElement resolved = resolveResult.getElement();
    PsiElement refGrandParent = referenceList.getParent();
    if (resolved instanceof PsiClass aClass) {
      if (refGrandParent instanceof PsiClass parentClass) {
        if (refGrandParent instanceof PsiTypeParameter typeParameter) {
          myGenericsChecker.checkElementInTypeParameterExtendsList(referenceList, typeParameter, resolveResult, ref);
        }
        else if (referenceList.equals(parentClass.getImplementsList()) || referenceList.equals(parentClass.getExtendsList())) {
          myClassChecker.checkExtendsClassAndImplementsInterface(referenceList, aClass, ref);
          if (!hasErrorResults() && referenceList.equals(parentClass.getExtendsList())) {
            myClassChecker.checkValueClassExtends(aClass, parentClass, ref);
          }
          if (!hasErrorResults()) {
            myClassChecker.checkCannotInheritFromFinal(aClass, ref);
          }
          if (!hasErrorResults()) {
            myClassChecker.checkExtendsProhibitedClass(aClass, parentClass, ref);
          }
          if (!hasErrorResults()) {
            // TODO: checkExtendsSealedClass
          }
          if (!hasErrorResults()) {
            myGenericsChecker.checkCannotInheritFromTypeParameter(aClass, ref);
          }
        }
      }
      else if (refGrandParent instanceof PsiMethod method && method.getThrowsList() == referenceList) {
        myMethodChecker.checkMustBeThrowable(aClass, ref);
      }
    }
    else if (refGrandParent instanceof PsiMethod method && referenceList == method.getThrowsList()) {
      report(JavaErrorKinds.METHOD_THROWS_CLASS_NAME_EXPECTED.create(ref));
    }
  }

  private JavaResolveResult @Nullable [] resolveOptimised(@NotNull PsiReferenceExpression expression) {
    try {
      if (expression instanceof PsiReferenceExpressionImpl) {
        PsiReferenceExpressionImpl.OurGenericsResolver resolver = PsiReferenceExpressionImpl.OurGenericsResolver.INSTANCE;
        return JavaResolveUtil.resolveWithContainingFile(expression, resolver, true, true, myFile);
      }
      else {
        return expression.multiResolve(true);
      }
    }
    catch (IndexNotReadyException e) {
      return null;
    }
  }

  static @Nullable JavaResolveResult resolveOptimised(@NotNull PsiJavaCodeReferenceElement ref, @NotNull PsiFile containingFile) {
    try {
      if (ref instanceof PsiReferenceExpressionImpl) {
        PsiReferenceExpressionImpl.OurGenericsResolver resolver = PsiReferenceExpressionImpl.OurGenericsResolver.INSTANCE;
        JavaResolveResult[] results = JavaResolveUtil.resolveWithContainingFile(ref, resolver, true, true, containingFile);
        return results.length == 1 ? results[0] : JavaResolveResult.EMPTY;
      }
      return ref.advancedResolve(true);
    }
    catch (IndexNotReadyException e) {
      return null;
    }
  }

  @Override
  public void visitReferenceList(@NotNull PsiReferenceList list) {
    super.visitReferenceList(list);
    if (list.getFirstChild() == null) return;
    PsiElement parent = list.getParent();
    if (!(parent instanceof PsiTypeParameter)) {
      myAnnotationChecker.checkAnnotationDeclaration(parent, list);
      if (!hasErrorResults()) myClassChecker.checkExtendsAllowed(list);
      if (!hasErrorResults()) myClassChecker.checkImplementsAllowed(list);
      if (!hasErrorResults()) myClassChecker.checkClassExtendsOnlyOneClass(list);
      if (!hasErrorResults()) myClassChecker.checkPermitsList(list);
    }
  }

  @Override
  public void visitExpression(@NotNull PsiExpression expression) {
    super.visitExpression(expression);
    if (!hasErrorResults()) myAnnotationChecker.checkConstantExpression(expression);
    if (!hasErrorResults()) myExpressionChecker.checkMustBeBoolean(expression);
    if (!hasErrorResults()) myExpressionChecker.checkAssertOperatorTypes(expression);
    if (!hasErrorResults()) myExpressionChecker.checkSynchronizedExpressionType(expression);
    if (expression.getParent() instanceof PsiArrayInitializerExpression arrayInitializer) {
      if (!hasErrorResults()) myExpressionChecker.checkArrayInitializer(expression, arrayInitializer);
    }
    if (!hasErrorResults() && expression instanceof PsiArrayAccessExpression accessExpression) {
      myExpressionChecker.checkValidArrayAccessExpression(accessExpression);
    }
  }

  @Override
  public void visitAnnotationArrayInitializer(@NotNull PsiArrayInitializerMemberValue initializer) {
    super.visitAnnotationArrayInitializer(initializer);
    myAnnotationChecker.checkArrayInitializer(initializer);
  }

  @Override
  public void visitMethod(@NotNull PsiMethod method) {
    super.visitMethod(method);
    PsiClass aClass = method.getContainingClass();
    if (!hasErrorResults() &&
        (method.hasModifierProperty(PsiModifier.DEFAULT) ||
         aClass != null && aClass.isInterface() && method.hasModifierProperty(PsiModifier.STATIC))) {
      checkFeature(method, JavaFeature.EXTENSION_METHODS);
    }
    if (!hasErrorResults() && aClass != null) {
      myMethodChecker.checkDuplicateMethod(aClass, method);
    }
    if (!hasErrorResults() && method.isConstructor()) {
      myClassChecker.checkThingNotAllowedInInterface(method);
    }
  }

  @Override
  public void visitAnnotationMethod(@NotNull PsiAnnotationMethod method) {
    PsiType returnType = method.getReturnType();
    PsiAnnotationMemberValue value = method.getDefaultValue();
    if (returnType != null && value != null) {
      myAnnotationChecker.checkMemberValueType(value, returnType, method);
    }
    PsiTypeElement typeElement = method.getReturnTypeElement();
    if (typeElement != null) {
      myAnnotationChecker.checkValidAnnotationType(returnType, typeElement);
    }

    PsiClass aClass = method.getContainingClass();
    if (typeElement != null && aClass != null) {
      myAnnotationChecker.checkCyclicMemberType(typeElement, aClass);
    }

    myAnnotationChecker.checkClashesWithSuperMethods(method);

    if (!hasErrorResults() && aClass != null) {
      myMethodChecker.checkDuplicateMethod(aClass, method);
    }
  }

  private static @NotNull UnhandledExceptions collectUnhandledExceptions(@NotNull PsiTryStatement statement) {
    UnhandledExceptions thrownTypes = UnhandledExceptions.EMPTY;

    PsiCodeBlock tryBlock = statement.getTryBlock();
    if (tryBlock != null) {
      thrownTypes = thrownTypes.merge(UnhandledExceptions.collect(tryBlock));
    }

    PsiResourceList resources = statement.getResourceList();
    if (resources != null) {
      thrownTypes = thrownTypes.merge(UnhandledExceptions.collect(resources));
    }

    return thrownTypes;
  }
  
  void checkFeature(@NotNull PsiElement element, @NotNull JavaFeature feature) {
    if (!feature.isSufficient(myLanguageLevel)) {
      report(JavaErrorKinds.UNSUPPORTED_FEATURE.create(element, feature));
    }
  }
}
