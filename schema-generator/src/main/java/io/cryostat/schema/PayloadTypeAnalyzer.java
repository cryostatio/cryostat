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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.resolution.types.ResolvedType;

/** Analyzes notification payload types and generates JSON Schema representations. */
public class PayloadTypeAnalyzer {

    private final Path sourceDir;
    private final Map<String, CompilationUnit> compilationUnitCache = new HashMap<>();
    private final Set<String> analyzedTypes = new HashSet<>();

    public PayloadTypeAnalyzer(Path sourceDir) {
        this.sourceDir = sourceDir;
    }

    /** Analyze the payload type from a notification site and generate its schema. */
    public Map<String, Object> analyze(NotificationSite site) {
        Expression payloadExpr = site.getPayloadExpression();
        return analyzeExpression(payloadExpr);
    }

    private Map<String, Object> analyzeExpression(Expression expr) {
        // Try to resolve the type using JavaParser's symbol solver
        try {
            ResolvedType resolvedType = expr.calculateResolvedType();
            String typeName = resolvedType.describe();

            // Handle Map.of(...) - common pattern for simple payloads
            if (typeName.startsWith("java.util.Map")) {
                return createMapSchema();
            }

            // Clean up the type name (remove package prefixes for project types)
            String simpleTypeName = extractSimpleTypeName(typeName);
            return analyzeType(simpleTypeName);

        } catch (Exception e) {
            // Fallback to heuristic analysis if type resolution fails
            System.err.println(
                    "Warning: Could not resolve type for expression: "
                            + expr
                            + " - "
                            + e.getMessage());
            return analyzeExpressionFallback(expr);
        }
    }

    /** Fallback analysis when type resolution fails. */
    private Map<String, Object> analyzeExpressionFallback(Expression expr) {
        // Handle Map.of(...) - common pattern for simple payloads
        if (expr instanceof MethodCallExpr) {
            MethodCallExpr methodCall = (MethodCallExpr) expr;
            if (methodCall.getNameAsString().equals("of")
                    && methodCall.getScope().map(s -> s.toString().equals("Map")).orElse(false)) {
                return createMapSchema();
            }
        }

        // Handle object creation: new SomePayload(...)
        if (expr instanceof ObjectCreationExpr) {
            ObjectCreationExpr objCreation = (ObjectCreationExpr) expr;
            String typeName = objCreation.getTypeAsString();
            return analyzeType(typeName);
        }

        return createObjectSchema("UnknownPayload");
    }

    /** Extract simple type name from fully qualified name. */
    private String extractSimpleTypeName(String fullyQualifiedName) {
        // Remove generic parameters
        String withoutGenerics = fullyQualifiedName.replaceAll("<.*>", "");

        // For nested classes like "io.cryostat.Foo.Bar", keep "Foo.Bar"
        // For regular classes like "io.cryostat.Foo", keep "Foo"
        if (withoutGenerics.contains(".")) {
            String[] parts = withoutGenerics.split("\\.");
            // If it looks like a nested class (last two parts start with uppercase)
            if (parts.length >= 2
                    && Character.isUpperCase(parts[parts.length - 2].charAt(0))
                    && Character.isUpperCase(parts[parts.length - 1].charAt(0))) {
                return parts[parts.length - 2] + "." + parts[parts.length - 1];
            }
            // Otherwise just return the simple name
            return parts[parts.length - 1];
        }
        return withoutGenerics;
    }

    private Map<String, Object> analyzeType(String typeName) {
        // Prevent infinite recursion
        if (analyzedTypes.contains(typeName)) {
            return createRefSchema(typeName);
        }
        analyzedTypes.add(typeName);

        // Check if it's a primitive or common type
        if (isPrimitiveOrCommonType(typeName)) {
            return createPrimitiveSchema(typeName);
        }

        // Try to find the type declaration in source files
        Optional<RecordDeclaration> recordDecl = findRecordDeclaration(typeName);
        if (recordDecl.isPresent()) {
            return analyzeRecord(recordDecl.get());
        }

        Optional<ClassOrInterfaceDeclaration> classDecl = findClassDeclaration(typeName);
        if (classDecl.isPresent()) {
            return analyzeClass(classDecl.get());
        }

        // Fallback: create a generic object schema
        return createObjectSchema(typeName);
    }

    private Map<String, Object> analyzeRecord(RecordDeclaration record) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        // Analyze record components
        record.getParameters()
                .forEach(
                        param -> {
                            String fieldName = param.getNameAsString();
                            String fieldType = param.getTypeAsString();
                            properties.put(fieldName, analyzeFieldType(fieldType));
                        });

        if (!properties.isEmpty()) {
            schema.put("properties", properties);
        }

        return schema;
    }

    private Map<String, Object> analyzeClass(ClassOrInterfaceDeclaration classDecl) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        // Analyze public fields
        classDecl
                .getFields()
                .forEach(
                        field -> {
                            if (field.isPublic()) {
                                field.getVariables()
                                        .forEach(
                                                var -> {
                                                    String fieldName = var.getNameAsString();
                                                    String fieldType = var.getTypeAsString();
                                                    properties.put(
                                                            fieldName, analyzeFieldType(fieldType));
                                                });
                            }
                        });

        if (!properties.isEmpty()) {
            schema.put("properties", properties);
        }

        return schema;
    }

    private Map<String, Object> analyzeFieldType(String fieldType) {
        // Remove generic type parameters for analysis
        String baseType = fieldType.replaceAll("<.*>", "");

        switch (baseType) {
            case "String":
                return Map.of("type", "string");
            case "int":
            case "Integer":
            case "long":
            case "Long":
                return Map.of("type", "integer");
            case "double":
            case "Double":
            case "float":
            case "Float":
                return Map.of("type", "number");
            case "boolean":
            case "Boolean":
                return Map.of("type", "boolean");
            case "Map":
                return createMapSchema();
            case "List":
            case "Set":
            case "Collection":
                return Map.of("type", "array");
            default:
                // For complex types, try to analyze them inline (avoid circular references)
                if (!analyzedTypes.contains(baseType)) {
                    return analyzeType(baseType);
                }
                // If already analyzed (circular reference), just describe it
                return createObjectSchema(baseType);
        }
    }

    private Map<String, Object> createMapSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", true);
        return schema;
    }

    private Map<String, Object> createObjectSchema(String typeName) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("description", "Payload of type " + typeName);
        return schema;
    }

    private Map<String, Object> createRefSchema(String typeName) {
        return Map.of("$ref", "#/components/schemas/" + typeName);
    }

    private Map<String, Object> createPrimitiveSchema(String typeName) {
        switch (typeName) {
            case "String":
                return Map.of("type", "string");
            case "Integer":
            case "Long":
                return Map.of("type", "integer");
            case "Double":
            case "Float":
                return Map.of("type", "number");
            case "Boolean":
                return Map.of("type", "boolean");
            default:
                return Map.of("type", "object");
        }
    }

    private boolean isPrimitiveOrCommonType(String typeName) {
        return typeName.equals("String")
                || typeName.equals("Integer")
                || typeName.equals("Long")
                || typeName.equals("Double")
                || typeName.equals("Float")
                || typeName.equals("Boolean")
                || typeName.equals("Map")
                || typeName.equals("List");
    }

    private Optional<RecordDeclaration> findRecordDeclaration(String typeName) {
        try (Stream<Path> paths = Files.walk(sourceDir)) {
            return paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .map(this::parseFile)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .flatMap(cu -> cu.findAll(RecordDeclaration.class).stream())
                    .filter(
                            record ->
                                    matchesTypeName(
                                            record.getNameAsString(),
                                            record.getFullyQualifiedName().orElse(""),
                                            typeName))
                    .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private Optional<ClassOrInterfaceDeclaration> findClassDeclaration(String typeName) {
        try (Stream<Path> paths = Files.walk(sourceDir)) {
            return paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .map(this::parseFile)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .flatMap(cu -> cu.findAll(ClassOrInterfaceDeclaration.class).stream())
                    .filter(
                            cls ->
                                    matchesTypeName(
                                            cls.getNameAsString(),
                                            cls.getFullyQualifiedName().orElse(""),
                                            typeName))
                    .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Check if a type matches the target type name. Handles: - Simple names: "TargetDiscoveryEvent"
     * - Nested class names: "Listener.TargetDiscoveryEvent" - Fully qualified names:
     * "io.cryostat.discovery.Listener.TargetDiscoveryEvent"
     */
    private boolean matchesTypeName(
            String simpleName, String fullyQualifiedName, String targetTypeName) {
        // Direct simple name match
        if (simpleName.equals(targetTypeName)) {
            return true;
        }

        // Check if fully qualified name ends with the target type name
        // This handles nested classes like "Listener.TargetDiscoveryEvent"
        if (fullyQualifiedName.endsWith("." + targetTypeName)
                || fullyQualifiedName.endsWith("$" + targetTypeName.replace(".", "$"))) {
            return true;
        }

        // Check if the fully qualified name matches exactly
        if (fullyQualifiedName.equals(targetTypeName)) {
            return true;
        }

        return false;
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
            return Optional.empty();
        }
    }
}
