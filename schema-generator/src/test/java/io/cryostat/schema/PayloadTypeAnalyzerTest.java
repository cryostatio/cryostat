/*
 * Copyright The Cryostat Authors.
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
package io.cryostat.schema;

import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.file.Path;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import org.junit.jupiter.api.Test;

class PayloadTypeAnalyzerTest {

    @Test
    void shouldResolveNestedRecordFromLexicalScope() {
        var compilationUnit =
                new JavaParser(
                                new ParserConfiguration()
                                        .setLanguageLevel(
                                                ParserConfiguration.LanguageLevel.JAVA_21))
                        .parse(
                                """
                                package example;

                                class First {
                                    record Payload(String first) {}
                                    record Event(Payload payload) {}
                                }

                                class Second {
                                    record Payload(String second) {}
                                    record Event(Payload payload) {}
                                }
                                """)
                        .getResult()
                        .orElseThrow();
        ClassOrInterfaceDeclaration second = compilationUnit.getClassByName("Second").orElseThrow();
        RecordDeclaration expected = findNestedRecord(second, "Payload");
        RecordDeclaration event = findNestedRecord(second, "Event");

        var analyzer = new PayloadTypeAnalyzer(Path.of("."));

        assertSame(
                expected,
                analyzer.findContextualRecordDeclaration(event.getParameter(0), "Payload")
                        .orElseThrow());
    }

    private static RecordDeclaration findNestedRecord(
            ClassOrInterfaceDeclaration enclosingType, String name) {
        return enclosingType.getMembers().stream()
                .filter(RecordDeclaration.class::isInstance)
                .map(RecordDeclaration.class::cast)
                .filter(record -> record.getNameAsString().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
