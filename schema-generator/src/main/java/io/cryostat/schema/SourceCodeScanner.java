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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;

/**
 * Scans Java source files to find all WebSocket notification publication sites. Looks for patterns
 * like: eventBus.publish(MessagingServer.class.getName(), new Notification(...))
 */
public class SourceCodeScanner {

    public SourceCodeScanner() {
        // JavaParser configuration (including symbol resolver) is set up centrally
        // in NotificationSchemaGenerator before any scanners are created
    }

    public List<NotificationSite> scan(Path sourceDir) throws IOException {
        List<NotificationSite> sites = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(sourceDir)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(
                            javaFile -> {
                                try {
                                    sites.addAll(scanFile(javaFile));
                                } catch (IOException e) {
                                    System.err.println(
                                            "Error scanning file "
                                                    + javaFile
                                                    + ": "
                                                    + e.getMessage());
                                }
                            });
        }

        return sites;
    }

    private List<NotificationSite> scanFile(Path javaFile) throws IOException {
        List<NotificationSite> sites = new ArrayList<>();

        CompilationUnit cu = StaticJavaParser.parse(javaFile);

        // Find all method calls named "publish"
        cu.findAll(MethodCallExpr.class)
                .forEach(
                        methodCall -> {
                            if (isNotificationPublish(cu, methodCall)) {
                                NotificationSite site =
                                        extractNotificationSite(javaFile, methodCall);
                                if (site != null) {
                                    sites.add(site);
                                }
                            }
                        });

        // Also scan for EntityNotificationObserver subclasses
        sites.addAll(scanEntityNotificationObservers(javaFile, cu));

        return sites;
    }

    /**
     * Scan for classes that extend EntityNotificationObserver and extract notification information
     * from their event handler methods.
     */
    private List<NotificationSite> scanEntityNotificationObservers(
            Path javaFile, CompilationUnit cu) {
        List<NotificationSite> sites = new ArrayList<>();

        cu.findAll(ClassOrInterfaceDeclaration.class)
                .forEach(
                        classDecl -> {
                            if (extendsEntityNotificationObserver(classDecl)) {
                                System.out.println(
                                        "DEBUG: Found EntityNotificationObserver subclass: "
                                                + classDecl.getNameAsString()
                                                + " in "
                                                + javaFile.getFileName());
                                List<NotificationSite> observerSites =
                                        extractNotificationSitesFromObserver(
                                                javaFile, cu, classDecl);
                                System.out.println(
                                        "DEBUG: Extracted "
                                                + observerSites.size()
                                                + " notification sites from "
                                                + classDecl.getNameAsString());
                                sites.addAll(observerSites);
                            }
                        });

        return sites;
    }

    /** Check if a class extends EntityNotificationObserver (directly or via Simple inner class). */
    private boolean extendsEntityNotificationObserver(ClassOrInterfaceDeclaration classDecl) {
        boolean result =
                classDecl.getExtendedTypes().stream()
                        .anyMatch(
                                type -> {
                                    String typeName = type.getNameAsString();
                                    String typeAsString = type.asString();
                                    boolean matches =
                                            typeName.equals("EntityNotificationObserver")
                                                    || typeName.contains(
                                                            "EntityNotificationObserver.Simple")
                                                    || typeAsString.contains(
                                                            "EntityNotificationObserver");
                                    if (!matches
                                            && classDecl.getNameAsString().contains("Observer")) {
                                        System.out.println(
                                                "DEBUG: Class "
                                                        + classDecl.getNameAsString()
                                                        + " extends "
                                                        + typeName
                                                        + " (full: "
                                                        + typeAsString
                                                        + ") - no match");
                                    }
                                    return matches;
                                });
        return result;
    }

    /**
     * Extract notification sites from an EntityNotificationObserver subclass. Looks for event
     * handler methods (onEntityCreated, onEntityUpdated, onEntityDeleted) and extracts the category
     * and payload type.
     */
    private List<NotificationSite> extractNotificationSitesFromObserver(
            Path javaFile, CompilationUnit cu, ClassOrInterfaceDeclaration observerClass) {
        List<NotificationSite> sites = new ArrayList<>();

        // Find the buildPayload method to get the payload type
        Optional<MethodDeclaration> buildPayloadMethod =
                observerClass.getMethodsByName("buildPayload").stream().findFirst();
        if (!buildPayloadMethod.isPresent()) {
            buildPayloadMethod =
                    observerClass.getMethodsByName("buildCreatedPayload").stream().findFirst();
        }

        if (!buildPayloadMethod.isPresent()) {
            System.out.println(
                    "DEBUG: No buildPayload method found in " + observerClass.getNameAsString());
            return sites; // No payload method found
        }

        System.out.println(
                "DEBUG: Found buildPayload method in " + observerClass.getNameAsString());

        // Extract payload type from the return statement
        Expression payloadExpr = extractPayloadExpression(buildPayloadMethod.get());
        if (payloadExpr == null) {
            System.out.println(
                    "DEBUG: No payload expression found in buildPayload method of "
                            + observerClass.getNameAsString());
            return sites;
        }

        System.out.println(
                "DEBUG: Found payload expression: "
                        + payloadExpr
                        + " in "
                        + observerClass.getNameAsString());

        // Find event handler methods and extract categories
        List<MethodDeclaration> eventHandlers =
                observerClass.getMethods().stream()
                        .filter(
                                method ->
                                        method.getNameAsString().startsWith("on")
                                                && method.getParameters().size() == 1)
                        .toList();

        System.out.println(
                "DEBUG: Found "
                        + eventHandlers.size()
                        + " event handler methods in "
                        + observerClass.getNameAsString());

        eventHandlers.forEach(
                eventHandler -> {
                    // Extract the event type from the parameter
                    String eventTypeName =
                            eventHandler
                                    .getParameter(0)
                                    .getType()
                                    .asClassOrInterfaceType()
                                    .getNameAsString();

                    System.out.println(
                            "DEBUG: Processing event handler "
                                    + eventHandler.getNameAsString()
                                    + " with event type: "
                                    + eventTypeName);

                    // Find the corresponding event class to get the category
                    Expression categoryExpr = findCategoryForEventType(cu, eventTypeName, javaFile);
                    if (categoryExpr != null) {
                        int lineNumber = eventHandler.getBegin().map(p -> p.line).orElse(-1);
                        sites.add(
                                new NotificationSite(
                                        javaFile.toString(),
                                        lineNumber,
                                        categoryExpr,
                                        payloadExpr,
                                        eventHandler));
                    } else {
                        System.out.println(
                                "DEBUG: No category found for event type: " + eventTypeName);
                    }
                });

        return sites;
    }

    /**
     * Extract the payload expression from a buildPayload method. Looks for return statements that
     * create new payload objects.
     */
    private Expression extractPayloadExpression(MethodDeclaration buildPayloadMethod) {
        // Find return statements in the method
        return buildPayloadMethod.findAll(com.github.javaparser.ast.stmt.ReturnStmt.class).stream()
                .map(ret -> ret.getExpression().orElse(null))
                .filter(expr -> expr instanceof ObjectCreationExpr)
                .findFirst()
                .orElse(null);
    }

    /**
     * Find the category constant for a given event type. Looks in the same package for the event
     * class and extracts the category from its getCategory() method.
     */
    private Expression findCategoryForEventType(
            CompilationUnit cu, String eventTypeName, Path javaFile) {
        // Extract base name from event type (e.g., "CredentialCreated" -> "Credential")
        // Event types typically end with "Created", "Updated", "Deleted", "Stopped", etc.
        String baseName =
                eventTypeName
                        .replaceAll("(Created|Updated|Deleted|Stopped|MetadataUpdated)$", "")
                        .replaceAll(".*\\.", "");

        System.out.println(
                "DEBUG: Looking for event file for " + eventTypeName + ", base name: " + baseName);

        // Try to find the event class file in the same directory
        // Pattern: <BaseName>Events.java (e.g., CredentialEvents.java)
        Path eventFilePath = javaFile.getParent().resolve(baseName + "Events.java");

        if (!Files.exists(eventFilePath)) {
            // Try with ActiveRecording prefix for recording events
            eventFilePath = javaFile.getParent().resolve("ActiveRecordingEvents.java");
        }

        if (!Files.exists(eventFilePath)) {
            return null;
        }

        try {
            CompilationUnit eventCu = StaticJavaParser.parse(eventFilePath);

            // Find the event class
            Optional<ClassOrInterfaceDeclaration> eventClass =
                    eventCu.findAll(ClassOrInterfaceDeclaration.class).stream()
                            .filter(
                                    cls ->
                                            eventTypeName.endsWith(cls.getNameAsString())
                                                    || eventTypeName.contains(
                                                            cls.getNameAsString()))
                            .findFirst();

            if (!eventClass.isPresent()) {
                return null;
            }

            // Find the getCategory() method
            Optional<MethodDeclaration> getCategoryMethod =
                    eventClass.get().getMethodsByName("getCategory").stream().findFirst();

            if (!getCategoryMethod.isPresent()) {
                return null;
            }

            // Extract the return expression
            return getCategoryMethod
                    .get()
                    .findAll(com.github.javaparser.ast.stmt.ReturnStmt.class)
                    .stream()
                    .map(ret -> ret.getExpression().orElse(null))
                    .filter(expr -> expr != null)
                    .findFirst()
                    .orElse(null);

        } catch (IOException e) {
            System.err.println("Error reading event file " + eventFilePath + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Check if this method call is a notification publish call. Pattern:
     * eventBus.publish(MessagingServer.class.getName(), new Notification(...))
     *
     * <p>This method verifies that:
     *
     * <ol>
     *   <li>The method is named "publish"
     *   <li>It's called on an EventBus instance (checked via imports and scope)
     *   <li>It has exactly 2 arguments
     *   <li>First argument is MessagingServer.class.getName()
     *   <li>Second argument is new Notification(...)
     * </ol>
     */
    private boolean isNotificationPublish(CompilationUnit cu, MethodCallExpr methodCall) {
        if (!methodCall.getNameAsString().equals("publish")) {
            return false;
        }

        // Check if the method is called on an EventBus instance
        if (!isEventBusPublish(cu, methodCall)) {
            return false;
        }

        // Must have exactly 2 arguments
        if (methodCall.getArguments().size() != 2) {
            return false;
        }

        Expression firstArg = methodCall.getArgument(0);
        Expression secondArg = methodCall.getArgument(1);

        // First argument should be MessagingServer.class.getName()
        boolean isMessagingServerTarget =
                firstArg.toString().contains("MessagingServer.class.getName()");

        // Second argument should be new Notification(...)
        boolean isNotificationCreation =
                secondArg instanceof ObjectCreationExpr
                        && ((ObjectCreationExpr) secondArg)
                                .getTypeAsString()
                                .equals("Notification");

        return isMessagingServerTarget && isNotificationCreation;
    }

    /**
     * Check if the publish method is being called on an EventBus instance. This checks:
     *
     * <ol>
     *   <li>The compilation unit imports io.vertx.mutiny.core.eventbus.EventBus
     *   <li>The scope (receiver) of the method call is a simple name like "bus" or "eventBus"
     * </ol>
     *
     * <p>This is a heuristic approach since full type resolution would require configuring
     * JavaParser's symbol solver with the entire classpath.
     */
    private boolean isEventBusPublish(CompilationUnit cu, MethodCallExpr methodCall) {
        // Check if EventBus is imported
        boolean hasEventBusImport =
                cu.getImports().stream()
                        .anyMatch(
                                imp ->
                                        imp.getNameAsString()
                                                .equals("io.vertx.mutiny.core.eventbus.EventBus"));

        if (!hasEventBusImport) {
            return false;
        }

        // Check the scope (what the method is called on)
        return methodCall
                .getScope()
                .map(
                        scope -> {
                            // Simple name like "bus" or "eventBus"
                            if (scope instanceof NameExpr) {
                                String name = ((NameExpr) scope).getNameAsString();
                                // Common EventBus variable names
                                return name.equals("bus")
                                        || name.equals("eventBus")
                                        || name.toLowerCase().contains("bus");
                            }
                            // Field access like "this.bus"
                            if (scope instanceof FieldAccessExpr) {
                                String fieldName = ((FieldAccessExpr) scope).getNameAsString();
                                return fieldName.equals("bus")
                                        || fieldName.equals("eventBus")
                                        || fieldName.toLowerCase().contains("bus");
                            }
                            return false;
                        })
                .orElse(false);
    }

    /** Extract notification details from a publish method call. */
    private NotificationSite extractNotificationSite(Path javaFile, MethodCallExpr methodCall) {
        Expression secondArg = methodCall.getArgument(1);

        if (!(secondArg instanceof ObjectCreationExpr)) {
            return null;
        }

        ObjectCreationExpr notificationCreation = (ObjectCreationExpr) secondArg;

        // new Notification(category, payload)
        if (notificationCreation.getArguments().size() != 2) {
            System.err.println(
                    "Warning: Unexpected Notification constructor arguments at "
                            + javaFile
                            + ":"
                            + methodCall.getBegin().map(p -> p.line).orElse(-1));
            return null;
        }

        Expression categoryExpr = notificationCreation.getArgument(0);
        Expression payloadExpr = notificationCreation.getArgument(1);

        int lineNumber = methodCall.getBegin().map(p -> p.line).orElse(-1);

        // Capture the enclosing method for context
        com.github.javaparser.ast.body.MethodDeclaration enclosingMethod =
                methodCall
                        .findAncestor(com.github.javaparser.ast.body.MethodDeclaration.class)
                        .orElse(null);

        return new NotificationSite(
                javaFile.toString(), lineNumber, categoryExpr, payloadExpr, enclosingMethod);
    }
}
