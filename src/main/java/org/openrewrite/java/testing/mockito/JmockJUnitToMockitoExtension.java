/*
 * Copyright 2021 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.java.testing.mockito;

import static java.util.Collections.EMPTY_LIST;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Parser;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.AddImport;
import org.openrewrite.java.DeleteStatement;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.format.AutoFormatVisitor;
import org.openrewrite.java.search.FindAnnotations;
import org.openrewrite.java.search.FindFieldsOfType;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.testing.junit5.UpdateTestAnnotation;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

public class JmockJUnitToMockitoExtension extends Recipe {

  @Override
  public String getDisplayName() {
    return "JMock `JUnitRuleMockery` to JUnit Jupiter `MockitoExtension`";
  }

  @Override
  public String getDescription() {
    return "Replaces `JUnitRuleMockery` rules with `MockitoExtension`.";
  }

  @Override
  public Duration getEstimatedEffortPerOccurrence() {
    return Duration.ofMinutes(5);
  }

  @Override
  protected TreeVisitor<?, ExecutionContext> getSingleSourceApplicableTest() {
    return new JavaIsoVisitor<ExecutionContext>() {
      @Override
      public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu,
          ExecutionContext executionContext) {
        doAfterVisit(new UsesType<>("org.jmock.integration.junit4.JUnitRuleMockery"));
        doAfterVisit(new UsesType<>("org.mockito.junit.MockitoTestRule"));
        doAfterVisit(new UsesType<>("org.mockito.junit.MockitoRule"));
        // doAfterVisit(new RemoveUnusedImports());
        return cu;
      }
    };
  }

  @Override
  protected TreeVisitor<?, ExecutionContext> getVisitor() {
    return new JmockJUnitToMockitoExtension.JMockJunitRuleToMockitoExtensionVisitor();
  }

  public static class JMockJunitRuleToMockitoExtensionVisitor
      extends JavaIsoVisitor<ExecutionContext> {
    private static final String MOCKITO_RULE_INVOCATION_KEY = "mockitoRuleInvocation";
    private static final String MOCKITO_TEST_RULE_INVOCATION_KEY = "mockitoTestRuleInvocation";
    private static final String JMOCK_TEST_RULE_INVOCATION_KEY = "jmockTestRuleInvocation";
    private static final String EXTEND_WITH_MOCKITO_EXTENSION =
        "@org.junit.jupiter.api.extension.ExtendWith(org.mockito.junit.jupiter.MockitoExtension"
            + ".class)";
    private static final String RUN_WITH_MOCKITO_JUNIT_RUNNER =
        "@org.junit.runner.RunWith(org.mockito.runners.MockitoJUnitRunner.class)";
    private static final Supplier<JavaParser> JAVA_PARSER = () ->
        JavaParser.fromJavaVersion()
            .dependsOn(Arrays.asList(
                Parser.Input.fromString("package org.mockito;\n" +
                    "public @interface Mock {\n" +
                    "}")
            )).build();

    @Override
    public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl,
        ExecutionContext ctx) {
      J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);
      Set<J.VariableDeclarations>
          jmockFields = FindFieldsOfType.find(cd, "org.jmock.integration.junit4.JUnitRuleMockery");

      Set<J.VariableDeclarations>
          mockitoFields = FindFieldsOfType.find(cd, "org.mockito.junit.MockitoRule");
      mockitoFields.addAll(FindFieldsOfType.find(cd, "org.mockito.junit.MockitoTestRule"));

      if (!jmockFields.isEmpty()) {
        List<Statement> statements = new ArrayList<>(cd.getBody().getStatements());
        statements.removeAll(jmockFields);
        cd = cd.withBody(cd.getBody().withStatements(statements));

        maybeRemoveImport("org.jmock.integration.junit4.JUnitRuleMockery");
        maybeRemoveImport("org.junit.Rule");
        maybeRemoveImport("org.jmock.imposters.ByteBuddyClassImposteriser");
        maybeRemoveImport("org.jmock.lib.concurrent.Synchroniser");

        if (classDecl.getBody().getStatements().size() != cd.getBody().getStatements().size() &&
            (FindAnnotations.find(classDecl.withBody(null), RUN_WITH_MOCKITO_JUNIT_RUNNER).isEmpty()
                &&
                FindAnnotations.find(classDecl.withBody(null), EXTEND_WITH_MOCKITO_EXTENSION)
                    .isEmpty())) {

          cd = cd.withTemplate(
              JavaTemplate.builder(this::getCursor, "@ExtendWith(MockitoExtension.class)")
                  .javaParser(() ->
                      JavaParser.fromJavaVersion()
                          .dependsOn(Arrays.asList(
                              Parser.Input.fromString("package org.junit.jupiter.api.extension;\n" +
                                  "public @interface ExtendWith {\n" +
                                  "Class[] value();\n" +
                                  "}"),
                              Parser.Input.fromString("package org.mockito.junit.jupiter;\n" +
                                  "public class MockitoExtension {\n" +
                                  "}")
                          )).build())
                  .imports("org.junit.jupiter.api.extension.ExtendWith",
                      "org.mockito.junit.jupiter.MockitoExtension")
                  .build(),
              cd.getCoordinates().addAnnotation(Comparator.comparing(J.Annotation::getSimpleName))
          );

          maybeAddImport("org.junit.jupiter.api.extension.ExtendWith");
          maybeAddImport("org.mockito.junit.jupiter.MockitoExtension");
        }
      }

      if (!mockitoFields.isEmpty()) {
        List<Statement> statements = new ArrayList<>(cd.getBody().getStatements());
        statements.removeAll(mockitoFields);
        cd = cd.withBody(cd.getBody().withStatements(statements));

        maybeRemoveImport("org.mockito.junit.MockitoRule");
        maybeRemoveImport("org.mockito.junit.MockitoTestRule");

        maybeRemoveImport("org.junit.Rule");
        maybeRemoveImport("org.mockito.junit.MockitoJUnit");
        maybeRemoveImport("org.mockito.quality.Strictness");

        if (classDecl.getBody().getStatements().size() != cd.getBody().getStatements().size() &&
            (FindAnnotations.find(classDecl.withBody(null), RUN_WITH_MOCKITO_JUNIT_RUNNER).isEmpty()
                &&
                FindAnnotations.find(classDecl.withBody(null), EXTEND_WITH_MOCKITO_EXTENSION)
                    .isEmpty())) {

          cd = cd.withTemplate(
              JavaTemplate.builder(this::getCursor, "@ExtendWith(MockitoExtension.class)")
                  .javaParser(() ->
                      JavaParser.fromJavaVersion()
                          .dependsOn(Arrays.asList(
                              Parser.Input.fromString("package org.junit.jupiter.api.extension;\n" +
                                  "public @interface ExtendWith {\n" +
                                  "Class[] value();\n" +
                                  "}"),
                              Parser.Input.fromString("package org.mockito.junit.jupiter;\n" +
                                  "public class MockitoExtension {\n" +
                                  "}")
                          )).build())
                  .imports("org.junit.jupiter.api.extension.ExtendWith",
                      "org.mockito.junit.jupiter.MockitoExtension")
                  .build(),
              cd.getCoordinates().addAnnotation(Comparator.comparing(J.Annotation::getSimpleName))
          );

          maybeAddImport("org.junit.jupiter.api.extension.ExtendWith");
          maybeAddImport("org.mockito.junit.jupiter.MockitoExtension");
        }
      }

      return cd;
    }

    @Override
    public J.Block visitBlock(J.Block block, ExecutionContext context) {

      J.Block blockOfCode = super.visitBlock(block, context);
      J.MethodInvocation method;
      boolean addImportForArgumentMatchers = false;

      if (blockOfCode.getStatements().get(0) instanceof J.MethodInvocation) {

        method = (J.MethodInvocation) blockOfCode.getStatements().get(0);

        if (!(nonNull(method.getMethodType()) && TypeUtils.isOfClassType(
            method.getMethodType().getDeclaringType(),
            "org.jmock.Mockery") && method.getSimpleName().equals("checking"))) {
          return blockOfCode;
        }

        final J.NewClass expression = (J.NewClass) method.getArguments().get(0);

        final J.Block statement = (J.Block) expression.getBody().getStatements().get(0);

        final List<Statement> statements = statement.getStatements();

        List<MockingElement> mockingElements = new ArrayList<>(statements.size());

        J.Identifier variableToMock = null;
        J.Identifier methodToMock = null;
        List<String> argumentsToMockedMethod = new ArrayList<>();
        J.Literal mockedReturnValue;
        Statement statementToDelete = method;

        for (Statement value : statements) {
          final J.MethodInvocation methodInvocation =
              (J.MethodInvocation) value;

          J.MethodInvocation select = null;

          if (nonNull(methodInvocation.getSelect())) {
            select = (J.MethodInvocation) methodInvocation.getSelect();
          }

          // oneOf Always Take 1 Argument
          if (nonNull(select) && select.getSimpleName().equalsIgnoreCase("oneOf")) {
            final Optional<Expression> argumentToOneOf =
                select.getArguments().stream().findFirst();

            if (!argumentToOneOf.isPresent()) {
              throw new IllegalArgumentException("OneOf must have a Single Argument");
            }

            final J.Identifier identifier = (J.Identifier) argumentToOneOf.get();

            variableToMock = identifier;
            methodToMock = methodInvocation.getName();

            for (Expression argument : methodInvocation.getArguments()) {
              if (argument instanceof J.Empty) {
                argumentsToMockedMethod = EMPTY_LIST;
              } else if (argument instanceof J.Literal) {
                final J.Literal literal = (J.Literal) argument;
                argumentsToMockedMethod.add(literal.getValueSource());
              } else if (argument instanceof J.MethodInvocation) {
                final J.MethodInvocation matcherMethodInvocation = (J.MethodInvocation) argument;

                if (matcherMethodInvocation.getSimpleName().equalsIgnoreCase("with")
                    && matcherMethodInvocation.getArguments().get(0) instanceof J.Literal) {
                  J.Literal matchingValue =
                      (J.Literal) matcherMethodInvocation.getArguments().get(0);
                  argumentsToMockedMethod.add("eq(" + matchingValue.getValueSource() + ")");
                  addImportForArgumentMatchers = true;
                } else if (matcherMethodInvocation.getSimpleName().equalsIgnoreCase("with")
                    && matcherMethodInvocation.getArguments()
                    .get(0) instanceof J.MethodInvocation) {
                  final J.MethodInvocation invocation =
                      (J.MethodInvocation) matcherMethodInvocation.getArguments().get(0);
                  J.FieldAccess fieldToMock = (J.FieldAccess) invocation.getArguments().get(0);
                  J.Identifier argumentIdentifier = (J.Identifier) fieldToMock.getTarget();
                  argumentsToMockedMethod.add(
                      "any(" + argumentIdentifier.getSimpleName() + ".class)");
                }
              }
            }
          }

          if (methodInvocation.getSimpleName().equalsIgnoreCase("will")) {
            //eg: will(returnValue(true))
            final Optional<Expression> argumentToReturnValue =
                methodInvocation.getArguments().stream().findFirst();

            if (!argumentToReturnValue.isPresent()) {
              throw new IllegalArgumentException("returnValue must have exactly 1 argument");
            }

            final J.MethodInvocation invocation =
                (J.MethodInvocation) argumentToReturnValue.get();

            mockedReturnValue = (J.Literal) invocation.getArguments().get(0);
            mockingElements.add(new MockingElement(variableToMock, methodToMock,
                argumentsToMockedMethod,
                mockedReturnValue));
          }
        }

        String mockingCodeWithTemplate = null;
        String verificationCodeWithTemplate = null;
        List<String> feedToMockingTemplate = new ArrayList<>();
        List<String> feedToVerificationTemplate = new ArrayList<>();
        for (MockingElement mockingElement : mockingElements) {
          if (isNull(mockingCodeWithTemplate)) {
            mockingCodeWithTemplate = "when(#{}).thenReturn(#{});";
            verificationCodeWithTemplate = "verify(#{}, times(1))#{};";
          } else {
            mockingCodeWithTemplate =
                mockingCodeWithTemplate.concat("\n").concat("when(#{}).thenReturn(#{});");
            verificationCodeWithTemplate =
                verificationCodeWithTemplate.concat("\n").concat("verify(#{}, times(1))#{};");
          }
          feedToMockingTemplate.add(invocationStatement(mockingElement));
          feedToMockingTemplate.add(mockingElement.getMockedResponseToReturn().getValueSource());
          feedToVerificationTemplate.add(mockingElement.getVariableToMock().getSimpleName());
          feedToVerificationTemplate.add(getMethodInvocationStatement(mockingElement));
        }

        if (nonNull(mockingCodeWithTemplate)) {
          blockOfCode = blockOfCode.withTemplate(
              JavaTemplate.builder(this::getCursor, mockingCodeWithTemplate)
                  .staticImports("org.mockito.Mockito.when")
                  .javaParser(JAVA_PARSER)
                  .build(),
              blockOfCode.getCoordinates().firstStatement(),
              feedToMockingTemplate.toArray(new String[0])
          );
        }

        if (nonNull(verificationCodeWithTemplate)) {
          blockOfCode = blockOfCode.withTemplate(
              JavaTemplate.builder(this::getCursor, verificationCodeWithTemplate)
                  .staticImports("org.mockito.Mockito.verify", "org.mockito.Mockito.times")
                  .javaParser(JAVA_PARSER)
                  .build(),
              blockOfCode.getCoordinates().lastStatement(),
              feedToVerificationTemplate.toArray(new String[0])
          );
        }

        if (addImportForArgumentMatchers) {
          doAfterVisit(new AddImport<>("org.mockito.ArgumentMatchers", "eq", false));
        }
        doAfterVisit(new DeleteStatement(statementToDelete));
        doAfterVisit(new UpdateTestAnnotation());
        doAfterVisit(new AddImport<>("org.mockito.Mockito", "times", false));
        doAfterVisit(new AddImport<>("org.mockito.Mockito", "verify", false));
        doAfterVisit(new AddImport<>("org.mockito.Mockito", "when", false));
        blockOfCode = maybeAutoFormat(block, blockOfCode, context);
      }

      return blockOfCode;
    }

    @Override
    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method,
        ExecutionContext ctx) {
      if (method.getMethodType() != null) {
        if (TypeUtils.isOfClassType(method.getMethodType().getDeclaringType(),
            "org.mockito.junit.MockitoRule")) {
          getCursor().putMessageOnFirstEnclosing(J.MethodDeclaration.class,
              MOCKITO_RULE_INVOCATION_KEY, method);
        } else if (TypeUtils.isOfClassType(method.getMethodType().getDeclaringType(),
            "org.mockito.junit.MockitoTestRule")) {
          getCursor().putMessageOnFirstEnclosing(J.MethodDeclaration.class,
              MOCKITO_TEST_RULE_INVOCATION_KEY, method);
        } else if (TypeUtils.isOfClassType(method.getMethodType().getDeclaringType(),
            "org.jmock.integration.junit4.JUnitRuleMockery")) {
          getCursor().putMessageOnFirstEnclosing(J.MethodDeclaration.class,
              JMOCK_TEST_RULE_INVOCATION_KEY, method);
        }
      }

      return method;
    }

    @NotNull
    private String invocationStatement(MockingElement mockingElement) {
      String methodToInvokeWithArguments = getMethodInvocationStatement(mockingElement);
      return mockingElement.getVariableToMock().getSimpleName().concat(methodToInvokeWithArguments);
    }

    private String getMethodInvocationStatement(MockingElement mockingElement) {
      String methodToInvokeWithArguments = "." + mockingElement.getMethodToMock() + "(";
      Iterator<String> iterator = mockingElement.getArgumentToMockedMethod().iterator();
      while (iterator.hasNext()) {
        String argument = iterator.next();
        methodToInvokeWithArguments = methodToInvokeWithArguments.concat(argument);
        if (iterator.hasNext()) {
          methodToInvokeWithArguments = methodToInvokeWithArguments.concat(",");
        }
      }
      methodToInvokeWithArguments = methodToInvokeWithArguments.concat(")");
      return methodToInvokeWithArguments;
    }

    @Override
    public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration methodDecl,
        ExecutionContext ctx) {
      J.MethodDeclaration m = super.visitMethodDeclaration(methodDecl, ctx);

      final J.MethodInvocation mockitoRuleInvocation =
          getCursor().pollMessage(MOCKITO_RULE_INVOCATION_KEY);
      final J.MethodInvocation mockitoTestRuleInvocation =
          getCursor().pollMessage(MOCKITO_TEST_RULE_INVOCATION_KEY);
      final J.MethodInvocation jmockTestRuleInvocation =
          getCursor().pollMessage(JMOCK_TEST_RULE_INVOCATION_KEY);

      if ((mockitoRuleInvocation != null || mockitoTestRuleInvocation != null
          || jmockTestRuleInvocation != null) && m.getBody() != null) {
        final List<Statement> filteredStatements = m.getBody().getStatements().stream()
            .filter(it -> !isTargetMethodInvocation(it))
            .collect(Collectors.toList());

        m = m.withBody((J.Block) new AutoFormatVisitor<ExecutionContext>()
            .visit(m.getBody().withStatements(filteredStatements), ctx, getCursor()));
      }

      return m;
    }

    @Override
    public J.VariableDeclarations visitVariableDeclarations(
        J.VariableDeclarations multiVariable, ExecutionContext executionContext) {
      J.VariableDeclarations mv =
          super.visitVariableDeclarations(multiVariable, executionContext);
      if (isAMockedVariable(mv)) {

        final String modifier = mv.getModifiers().stream()
            .map(it -> it.getType().name().toLowerCase()).collect(Collectors.joining(" "));

        final String variableName = mv.getVariables().stream()
            .map(fv -> fv.withInitializer(null))
            .map(J::print).collect(Collectors.joining(","));

        final String variableType = mv.getTypeExpression().toString();

        mv = mv.withTemplate(
            JavaTemplate.builder(this::getCursor, "@Mock\n#{} #{} #{};")
                .imports("org.mockito.Mock")
                .javaParser(() ->
                    JavaParser.fromJavaVersion()
                        .dependsOn(Arrays.asList(
                            Parser.Input.fromString("package org.mockito;\n" +
                                "public @interface Mock {\n" +
                                "}")
                        )).build())
                // .doAfterVariableSubstitution(t -> System.out.println(t))
                .build(),
            multiVariable.getCoordinates().replace(),
            modifier,
            variableType,
            variableName
        );
        maybeAddImport("org.mockito.Mock");
        return mv;
      }

      final List<String> annotationNames =
          mv.getLeadingAnnotations().stream().map(x -> x.getSimpleName())
              .collect(Collectors.toList());

      if (annotationNames.contains("Mock")) {
        maybeRemoveImport("org.jmock.auto.Mock");
        mv = mv.withTemplate(
            JavaTemplate.builder(this::getCursor, "@Mock")
                .javaParser(() ->
                    JavaParser.fromJavaVersion()
                        .dependsOn(Arrays.asList(
                            Parser.Input.fromString("package org.mockito;\n" +
                                "public @interface Mock {\n" +
                                "}")
                        )).build())
                .imports("org.mockito.Mock")
                // .doAfterVariableSubstitution(t -> System.out.println(t))
                .build(),
            mv.getCoordinates().replaceAnnotations()
        );

        maybeAddImport("org.mockito.Mock");
        return mv;
      }
      return mv;
    }

    private boolean isAMockedVariable(J.VariableDeclarations multiVariable) {

      if (!(multiVariable.getVariables().get(0).getInitializer() instanceof J.MethodInvocation)) {
        return false;
      }

      final J.MethodInvocation initializer =
          (J.MethodInvocation) multiVariable.getVariables().get(0).getInitializer();
      return initializer.getMethodType().getName().equals("mock") &&
          initializer.getMethodType().getDeclaringType().getFullyQualifiedName()
              .equals("org.jmock.Mockery");
    }

    private static boolean isTargetMethodInvocation(Statement statement) {
      if (!(statement instanceof J.MethodInvocation)) {
        return false;
      }
      final J.MethodInvocation m = (J.MethodInvocation) statement;
      if (m.getMethodType() == null) {
        return false;
      }

      return TypeUtils.isOfClassType(m.getMethodType().getDeclaringType(),
          "org.jmock.integration.junit4.JUnitRuleMockery") ||
          TypeUtils.isOfClassType(m.getMethodType().getDeclaringType(),
              "org.mockito.junit.MockitoRule") ||
          TypeUtils.isOfClassType(m.getMethodType().getDeclaringType(),
              "org.mockito.junit.MockitoTestRule");
    }

    private final class MockingElement {
      private final J.Identifier variableToMock;
      private final J.Identifier methodToMock;
      private final List<String> argumentToMockedMethod;
      private final J.Literal mockedResponseToReturn;

      public J.Identifier getVariableToMock() {
        return variableToMock;
      }

      public J.Identifier getMethodToMock() {
        return methodToMock;
      }

      public List<String> getArgumentToMockedMethod() {
        return Collections.unmodifiableList(argumentToMockedMethod);
      }

      public J.Literal getMockedResponseToReturn() {
        return mockedResponseToReturn;
      }

      public MockingElement(J.Identifier variableToMock, J.Identifier methodToMock,
          List<String> argumentToMockedMethod,
          J.Literal mockedResponseToReturn) {
        this.variableToMock = variableToMock;
        this.methodToMock = methodToMock;
        this.argumentToMockedMethod = argumentToMockedMethod;
        this.mockedResponseToReturn = mockedResponseToReturn;
      }
    }
  }
}
