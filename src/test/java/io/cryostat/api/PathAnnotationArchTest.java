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
package io.cryostat.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * Enforces REST API path naming conventions. All @Path annotation values must consist only of
 * lowercase alphanumerics, hyphens, forward slashes, and curly braces (for path parameters).
 */
@AnalyzeClasses(packages = "io.cryostat", importOptions = ImportOption.DoNotIncludeTests.class)
public class PathAnnotationArchTest {

    private static final Logger logger = Logger.getLogger(PathAnnotationArchTest.class);

    private static final Pattern VALID_PATH_PATTERN = Pattern.compile("^[a-z0-9/\\-{}]*$");

    @Test
    void allPathAnnotationsFollowNamingConvention() {
        JavaClasses classes =
                new ClassFileImporter()
                        .withImportOption(new ImportOption.DoNotIncludeTests())
                        .importPackages("io.cryostat");

        List<String> violations = new ArrayList<>();
        List<String> validatedPaths = new ArrayList<>();

        classes.forEach(
                javaClass -> {
                    // Skip REST client interfaces - just test endpoints we define
                    if (javaClass.isAnnotatedWith(RegisterRestClient.class)) {
                        return;
                    }

                    String classPath = "";
                    if (javaClass.isAnnotatedWith(Path.class)) {
                        Path pathAnnotation = javaClass.getAnnotationOfType(Path.class);
                        classPath = pathAnnotation.value();
                        if (!isValidPath(classPath)) {
                            violations.add(
                                    String.format(
                                            "Class %s has invalid @Path value: '%s'",
                                            javaClass.getFullName(), classPath));
                        } else if (!classPath.isEmpty()) {
                            validatedPaths.add(classPath);
                        }
                    }

                    final String finalClassPath = classPath;
                    javaClass
                            .getMethods()
                            .forEach(
                                    method -> {
                                        if (method.isAnnotatedWith(Path.class)) {
                                            Path pathAnnotation =
                                                    method.getAnnotationOfType(Path.class);
                                            String methodPath = pathAnnotation.value();
                                            String fullPath =
                                                    combinePaths(finalClassPath, methodPath);
                                            if (!isValidPath(methodPath)) {
                                                violations.add(
                                                        String.format(
                                                                "Method %s has invalid @Path value:"
                                                                        + " '%s'",
                                                                method.getFullName(), methodPath));
                                            } else {
                                                validatedPaths.add(fullPath);
                                            }
                                        }
                                    });
                });

        logger.debug("\n========== PATH ANNOTATION VALIDATION RESULTS ==========");
        logger.debug("Total @Path annotations validated: " + validatedPaths.size());
        logger.debug("\nAll validated paths (sorted):");
        validatedPaths.stream().sorted().forEach(p -> logger.debug("  ✓ " + p));

        assertTrue(
                violations.isEmpty(),
                String.format(
                        "Found %d @Path annotations with invalid naming convention:\n%s",
                        violations.size(), String.join("\n", violations)));

        logger.debug("========== ALL PATHS VALIDATED SUCCESSFULLY ==========\n");
    }

    private static String combinePaths(String classPath, String methodPath) {
        if (classPath.isEmpty() && methodPath.isEmpty()) {
            return "";
        }
        if (methodPath.isEmpty()) {
            return classPath;
        }
        if (classPath.isEmpty()) {
            return methodPath;
        }
        String cleanClass =
                classPath.endsWith("/")
                        ? classPath.substring(0, classPath.length() - 1)
                        : classPath;
        String cleanMethod = methodPath.startsWith("/") ? methodPath : "/" + methodPath;
        return cleanClass + cleanMethod;
    }

    private static boolean isValidPath(String pathValue) {
        // Empty strings are valid (used on classes where methods define absolute paths)
        if (pathValue.isEmpty()) {
            return true;
        }

        // Remove all {parameter} segments and check if what's left is valid
        String withoutParams = pathValue.replaceAll("\\{[^}]+\\}", "");
        return VALID_PATH_PATTERN.matcher(withoutParams).matches();
    }
}
