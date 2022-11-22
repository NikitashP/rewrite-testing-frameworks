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

import static org.openrewrite.java.Assertions.java;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.openrewrite.Parser;
import org.openrewrite.Recipe;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaRecipeTest;
import org.openrewrite.java.testing.assertj.JUnitAssertEqualsToAssertThat;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

class JmockMockingToMockitoMocking implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec
          .parser(JavaParser.fromJavaVersion()
            .classpath("junit", "hamcrest", "mockito-core", "jmock", "jmock-junit4")
            .dependsOn(List.of(
              Parser.Input.fromString("package org.junit.jupiter.api.extension;\n" +
                "public @interface ExtendWith {\n" +
                "Class[] value();\n" +
                "}"),
              Parser.Input.fromString("package org.mockito.junit.jupiter;\n" +
                "public class MockitoExtension {\n" +
                "}")
            ))).recipe(new JmockJUnitToMockitoExtension());
    }

    @Test
    void replaceJUnitRuleWithExtension() {
        rewriteRun(
          java("""
                          import org.jmock.integration.junit4.JUnitRuleMockery;
                          import org.jmock.imposters.ByteBuddyClassImposteriser;
                          import org.jmock.lib.concurrent.Synchroniser;
                          import org.junit.Rule;
                          
                          public class A {
                          
                            @Rule
                            public JUnitRuleMockery context = new JUnitRuleMockery() {
                                {
                                  setImposteriser(ByteBuddyClassImposteriser.INSTANCE);
                                  setThreadingPolicy(new Synchroniser());
                                }
                              };

                          }
              """,
            """
                          import org.junit.jupiter.api.extension.ExtendWith;
                          import org.mockito.junit.jupiter.MockitoExtension;
                          
                          @ExtendWith(MockitoExtension.class)
                          public class A {

                          }
              """
          ));

    }

    @Test
    void replaceJmockingMockAnnotationWithMockitoAnnotation() {

        rewriteRun(
          java("""
                          import org.jmock.auto.Mock;
                          import org.jmock.imposters.ByteBuddyClassImposteriser;
                          import org.jmock.integration.junit4.JUnitRuleMockery;
                          import org.jmock.lib.concurrent.Synchroniser;
                          import org.junit.Rule;
                          import java.util.List;
                          
                          public class A {
                          
                            @Rule
                            public JUnitRuleMockery context = new JUnitRuleMockery() {
                              {
                                setImposteriser(ByteBuddyClassImposteriser.INSTANCE);
                                setThreadingPolicy(new Synchroniser());
                              }
                            };
                            @Mock
                            List<String> mockedList;
                            
                          }
              """,
            """
                          import org.junit.jupiter.api.extension.ExtendWith;
                          import org.mockito.Mock;
                          import org.mockito.junit.jupiter.MockitoExtension;
                          
                          import java.util.List;
                          
                          @ExtendWith(MockitoExtension.class)
                          public class A {
                              @Mock
                              List<String> mockedList;
                            
                          }
              """
          ));
    }

    @Test
    void replaceContextMockingToMockitoMocking() {
        rewriteRun(
          java("""
                          import org.jmock.imposters.ByteBuddyClassImposteriser;
                          import org.jmock.integration.junit4.JUnitRuleMockery;
                          import org.jmock.lib.concurrent.Synchroniser;
                          import org.junit.Rule;
                          import java.util.List;
                          
                          public class A {
                          
                            @Rule
                            public JUnitRuleMockery context = new JUnitRuleMockery() {
                              {
                                setImposteriser(ByteBuddyClassImposteriser.INSTANCE);
                                setThreadingPolicy(new Synchroniser());
                              }
                            };
                            
                            List<String> mockedList = context.mock(List.class);
                            
                          }
              """,
            """
                          import org.junit.jupiter.api.extension.ExtendWith;
                          import org.mockito.Mock;
                          import org.mockito.junit.jupiter.MockitoExtension;
                          
                          import java.util.List;
                          
                          @ExtendWith(MockitoExtension.class)
                          public class A {
                          
                              @Mock
                              List<String>  mockedList;
                            
                          }
              """
          ));
    }

    @Test
    void replaceJmockExpectationWithMockitoExpectation() {
        rewriteRun(
          spec -> spec.typeValidationOptions(TypeValidation.none().methodInvocations(false)),
          java("""
                          import static org.hamcrest.MatcherAssert.assertThat;
                          import static org.hamcrest.Matchers.is;
                          
                          import java.util.List;
                          import org.jmock.auto.Mock;
                          import org.jmock.Expectations;
                          import org.jmock.integration.junit4.JUnitRuleMockery;
                          import org.junit.Rule;
                          import org.junit.Test;
                          
                          public class A {
                          
                            @Rule
                            public JUnitRuleMockery context = new JUnitRuleMockery();
                            @Mock
                            List<String> mockedList;
                          
                            @Test
                            public void testList() {
                              context.checking(new Expectations() {{
                                oneOf(mockedList).add("one");
                                will(returnValue(true));
                          
                                oneOf(mockedList).size();
                                will(returnValue(100));
                              }});
                              final boolean wasAdded = mockedList.add("one");
                              assertThat(wasAdded, is(true));
                              final int sizeOfList = mockedList.size();
                              assertThat(sizeOfList,is(100));
                            }
                          }
              """,
            """
                          import static org.hamcrest.MatcherAssert.assertThat;
                          import static org.hamcrest.Matchers.is;
                          import static org.mockito.Mockito.*;
                          
                          import java.util.List;
                          import org.junit.jupiter.api.Test;
                          import org.junit.jupiter.api.extension.ExtendWith;
                          import org.mockito.Mock;
                          import org.mockito.junit.jupiter.MockitoExtension;
                          
                          @ExtendWith(MockitoExtension.class)
                          public class A {
                              @Mock
                              List<String> mockedList;
                          
                              @Test
                              void testList() {
                                  when(mockedList.add("one")).thenReturn(true);
                                  when(mockedList.size()).thenReturn(100);
                                  final boolean wasAdded = mockedList.add("one");
                                  assertThat(wasAdded, is(true));
                                  final int sizeOfList = mockedList.size();
                                  assertThat(sizeOfList, is(100));
                                  verify(mockedList, times(1)).add("one");
                                  verify(mockedList, times(1)).size();
                              }
                          }
              """
          ));
    }

    @Test
    void replaceJmockWhenObjectReferenceExists() {
        rewriteRun(
          spec -> spec.typeValidationOptions(TypeValidation.none().methodInvocations(false)),
          java("""
                          import static org.hamcrest.MatcherAssert.assertThat;
                          import static org.hamcrest.Matchers.is;
                          
                          import java.util.ArrayList;
                          import java.util.List;
                          import org.jmock.Expectations;
                          import org.jmock.imposters.ByteBuddyClassImposteriser;
                          import org.jmock.integration.junit4.JUnitRuleMockery;
                          import org.jmock.lib.concurrent.Synchroniser;
                          import org.junit.Rule;
                          import org.junit.Test;
                          
                          public class A {
                          
                            @Rule
                            public JUnitRuleMockery context = new JUnitRuleMockery() {
                              {
                                setImposteriser(ByteBuddyClassImposteriser.INSTANCE);
                                setThreadingPolicy(new Synchroniser());
                              }
                            };
                            Temp testClass = context.mock(Temp.class);
                          
                            @Test
                            public void testList() {
                              context.checking(new Expectations() {{
                                oneOf(testClass).addValues(with("val1"), with(any(String.class)));
                                will(returnValue(true));
                              }});
                          
                              final boolean wasAdded = testClass.addValues("val1", "val2");
                              assertThat(wasAdded, is(true));
                            }
                          
                            class Temp {
                              List<String> listOne = new ArrayList<>();
                              List<String> listTwo = new ArrayList<>();
                          
                              public boolean addValues(String valForListOne, String valForListTwo) {
                                return listOne.add(valForListOne) && listTwo.add(valForListTwo);
                              }
                            }
                          }
              """,
            """
                          import static org.hamcrest.MatcherAssert.assertThat;
                          import static org.hamcrest.Matchers.is;
                          import static org.mockito.ArgumentMatchers.eq;
                          import static org.mockito.Mockito.*;
                          
                          import java.util.ArrayList;
                          import java.util.List;
                          import org.junit.jupiter.api.Test;
                          import org.junit.jupiter.api.extension.ExtendWith;
                          import org.mockito.Mock;
                          import org.mockito.junit.jupiter.MockitoExtension;
                          
                          @ExtendWith(MockitoExtension.class)
                          public class A {
                              @Mock
                              Temp  testClass;
                          
                              @Test
                              void testList() {
                                  when(testClass.addValues(eq("val1"), any(String.class))).thenReturn(true);
                          
                                  final boolean wasAdded = testClass.addValues("val1", "val2");
                                  assertThat(wasAdded, is(true));
                                  verify(testClass, times(1)).addValues(eq("val1"), any(String.class));
                              }
                          
                            class Temp {
                              List<String> listOne = new ArrayList<>();
                              List<String> listTwo = new ArrayList<>();
                          
                              public boolean addValues(String valForListOne, String valForListTwo) {
                                return listOne.add(valForListOne) && listTwo.add(valForListTwo);
                              }
                            }
                          }
              """
          ));
    }

    //@Test
    void fullMigrationTest() {

        rewriteRun(
          java("""
                          import static org.hamcrest.MatcherAssert.assertThat;
                          import static org.hamcrest.Matchers.is;
                          
                          import java.util.List;
                          import org.jmock.auto.Mock;
                          import org.jmock.Expectations;
                          import org.jmock.integration.junit4.JUnitRuleMockery;
                          import org.junit.Rule;
                          import org.junit.Test;
                          
                          public class A {
                          
                            @Rule
                            public JUnitRuleMockery context = new JUnitRuleMockery();
                            @Mock
                            List<String> mockedList;
                          
                            @Test
                            public void testList() {
                              context.checking(new Expectations() {{
                                oneOf (mockedList).add("one");
                                will(returnValue(true));
                          
                                oneOf(mockedList).size();
                                will(returnValue(100));
                              }});
                          
                              final boolean wasAdded = mockedList.add("one");
                              assertThat(wasAdded, is(true));
                              final int sizeOfList = mockedList.size();
                              assertThat(sizeOfList,is(100));
                            }
                          }
              """,
            """
                          import static org.hamcrest.Matchers.is;
                          import static org.junit.Assert.assertThat;
                          import static org.mockito.Mockito.times;
                          import static org.mockito.Mockito.verify;
                          import static org.mockito.Mockito.when;
                          
                          import java.util.List;
                          import org.junit.jupiter.api.Test;
                          import org.junit.jupiter.api.extension.ExtendWith;
                          import org.mockito.Mock;
                          import org.mockito.junit.jupiter.MockitoExtension;
                          
                          @ExtendWith(MockitoExtension.class)
                          public class A {
                            @Mock
                            List<String> mockedList;
                          
                            @Test
                            void testList() {
                              when(mockedList.add("one")).thenReturn(true);
                              when(mockedList.size()).thenReturn(100);
                              final boolean wasAdded = mockedList.add("one");
                              assertThat(wasAdded, is(true));
                              final int sizeOfList = mockedList.size();
                              assertThat(sizeOfList, is(100));
                              verify(mockedList, times(1)).add("one");
                              verify(mockedList, times(1)).size();
                            }
                          }
              """
          ));

    }
}