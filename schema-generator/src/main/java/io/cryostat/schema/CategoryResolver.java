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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;

/**
 * Resolves notification category strings from various expression types: - String literals:
 * "CategoryName" - Constants: CONSTANT_NAME - Enum values: EventCategory.CREATED.category() -
 * Chained calls: event.category().category()
 */
public class CategoryResolver {

    private final Path sourceDir;
    private final Map<String, String> constantCache = new HashMap<>();
    private final Map<String, CompilationUnit> compilationUnitCache = new HashMap<>();

    public CategoryResolver(Path sourceDir) {
        this.sourceDir = sourceDir;
        buildConstantCache();
    }

    /** Resolve the category string from a notification site. */
    public String resolve(NotificationSite site) {
        Expression categoryExpr = site.getCategoryExpression();
        return resolveExpression(categoryExpr);
    }

    private String resolveExpression(Expression expr) {
        // Pattern 1: String literal
        if (expr instanceof StringLiteralExpr) {
            return ((StringLiteralExpr) expr).getValue();
        }

        // Pattern 2: Simple name reference (constant)
        if (expr instanceof NameExpr) {
            String name = ((NameExpr) expr).getNameAsString();
            return constantCache.getOrDefault(name, "UNRESOLVED:" + name);
        }

        // Pattern 3: Field access (e.g., SomeClass.CONSTANT)
        if (expr instanceof FieldAccessExpr) {
            FieldAccessExpr fieldAccess = (FieldAccessExpr) expr;
            String fieldName = fieldAccess.getNameAsString();
            return constantCache.getOrDefault(fieldName, "UNRESOLVED:" + fieldName);
        }

        // Pattern 4: Method call (e.g., event.category().category() or
        // EventCategory.CREATED.category())
        if (expr instanceof MethodCallExpr) {
            return resolveMethodCall((MethodCallExpr) expr);
        }

        return "UNRESOLVED:" + expr.toString();
    }

    private String resolveMethodCall(MethodCallExpr methodCall) {
        String methodName = methodCall.getNameAsString();

        // If it's a .category() call, resolve the scope
        if (methodName.equals("category")) {
            Optional<Expression> scope = methodCall.getScope();
            if (scope.isPresent()) {
                Expression scopeExpr = scope.get();

                // Recursive case: event.category().category()
                if (scopeExpr instanceof MethodCallExpr) {
                    return resolveMethodCall((MethodCallExpr) scopeExpr);
                }

                // Enum constant case: EventCategory.CREATED.category()
                if (scopeExpr instanceof FieldAccessExpr) {
                    return resolveEnumConstant((FieldAccessExpr) scopeExpr);
                }

                // Variable case: event.category()
                // This would require more complex data flow analysis
                return "UNRESOLVED:method_call:" + methodCall.toString();
            }
        }

        return "UNRESOLVED:method_call:" + methodCall.toString();
    }

    private String resolveEnumConstant(FieldAccessExpr fieldAccess) {
        String enumConstantName = fieldAccess.getNameAsString();

        // Try to find the enum constant value from cache
        String cached = constantCache.get(enumConstantName);
        if (cached != null) {
            return cached;
        }

        // If not in cache, try to resolve from enum declaration
        Expression scope = fieldAccess.getScope();
        if (scope instanceof NameExpr) {
            String enumTypeName = ((NameExpr) scope).getNameAsString();
            return resolveEnumConstantFromType(enumTypeName, enumConstantName);
        } else if (scope instanceof FieldAccessExpr) {
            // Nested class case: OuterClass.InnerEnum.CONSTANT
            String fullTypeName = scope.toString();
            return resolveEnumConstantFromType(fullTypeName, enumConstantName);
        }

        return "UNRESOLVED:enum:" + fieldAccess.toString();
    }

    private String resolveEnumConstantFromType(String enumTypeName, String constantName) {
        // Search for enum declaration in source files
        try (Stream<Path> paths = Files.walk(sourceDir)) {
            return paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .map(this::parseFile)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .flatMap(cu -> cu.findAll(EnumDeclaration.class).stream())
                    .filter(
                            enumDecl ->
                                    enumDecl.getNameAsString().equals(enumTypeName)
                                            || enumDecl.getFullyQualifiedName()
                                                    .map(fqn -> fqn.endsWith(enumTypeName))
                                                    .orElse(false))
                    .flatMap(enumDecl -> enumDecl.getEntries().stream())
                    .filter(entry -> entry.getNameAsString().equals(constantName))
                    .findFirst()
                    .flatMap(this::extractEnumConstantValue)
                    .orElse("UNRESOLVED:enum:" + enumTypeName + "." + constantName);
        } catch (IOException e) {
            return "UNRESOLVED:enum:" + enumTypeName + "." + constantName;
        }
    }

    private Optional<String> extractEnumConstantValue(EnumConstantDeclaration enumConstant) {
        // Look for constructor argument (string literal)
        if (!enumConstant.getArguments().isEmpty()) {
            Expression firstArg = enumConstant.getArgument(0);
            if (firstArg instanceof StringLiteralExpr) {
                return Optional.of(((StringLiteralExpr) firstArg).getValue());
            }
            // If it's a constant reference, resolve it
            if (firstArg instanceof NameExpr) {
                String name = ((NameExpr) firstArg).getNameAsString();
                return Optional.ofNullable(constantCache.get(name));
            }
        }
        return Optional.empty();
    }

    /** Build a cache of all string constants in the source directory. */
    private void buildConstantCache() {
        try (Stream<Path> paths = Files.walk(sourceDir)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(this::cacheConstantsFromFile);
        } catch (IOException e) {
            System.err.println("Error building constant cache: " + e.getMessage());
        }
    }

    private void cacheConstantsFromFile(Path javaFile) {
        parseFile(javaFile)
                .ifPresent(
                        cu -> {
                            // Cache string constants
                            cu.findAll(FieldDeclaration.class)
                                    .forEach(
                                            field -> {
                                                if (field.isStatic() && field.isFinal()) {
                                                    field.getVariables()
                                                            .forEach(
                                                                    var -> {
                                                                        var.getInitializer()
                                                                                .ifPresent(
                                                                                        init -> {
                                                                                            if (init
                                                                                                    instanceof
                                                                                                    StringLiteralExpr) {
                                                                                                String
                                                                                                        value =
                                                                                                                ((StringLiteralExpr)
                                                                                                                                init)
                                                                                                                        .getValue();
                                                                                                constantCache
                                                                                                        .put(
                                                                                                                var
                                                                                                                        .getNameAsString(),
                                                                                                                value);
                                                                                            }
                                                                                        });
                                                                    });
                                                }
                                            });
                        });
    }

    private Optional<CompilationUnit> parseFile(Path javaFile) {
        try {
            String key = javaFile.toString();
            if (!compilationUnitCache.containsKey(key)) {
                CompilationUnit cu = StaticJavaParser.parse(javaFile);
                compilationUnitCache.put(key, cu);
            }
            return Optional.of(compilationUnitCache.get(key));
        } catch (IOException e) {
            System.err.println("Error parsing file " + javaFile + ": " + e.getMessage());
            return Optional.empty();
        }
    }
}
