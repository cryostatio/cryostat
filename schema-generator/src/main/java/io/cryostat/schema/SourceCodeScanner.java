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
import java.util.stream.Stream;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
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
        // Configure JavaParser to support Java 21 features
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        StaticJavaParser.setConfiguration(config);
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

        return sites;
    }

    /**
     * Check if this method call is a notification publish call. Pattern:
     * eventBus.publish(MessagingServer.class.getName(), new Notification(...))
     *
     * <p>This method verifies that:
     * <ol>
     *   <li>The method is named "publish"</li>
     *   <li>It's called on an EventBus instance (checked via imports and scope)</li>
     *   <li>It has exactly 2 arguments</li>
     *   <li>First argument is MessagingServer.class.getName()</li>
     *   <li>Second argument is new Notification(...)</li>
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
     * <ol>
     *   <li>The compilation unit imports io.vertx.mutiny.core.eventbus.EventBus</li>
     *   <li>The scope (receiver) of the method call is a simple name like "bus" or "eventBus"</li>
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

        return new NotificationSite(javaFile.toString(), lineNumber, categoryExpr, payloadExpr);
    }
}
