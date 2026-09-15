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
package io.cryostat.security;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import io.cryostat.security.rbac.graphql.RequiresPermission;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.properties.CanBeAnnotated;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import io.quarkus.security.PermissionsAllowed;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Query;
import org.eclipse.microprofile.graphql.Source;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.junit.jupiter.api.Test;

@AnalyzeClasses(packages = "io.cryostat", importOptions = ImportOption.DoNotIncludeTests.class)
public class PermissionAnnotationArchTest {

    private static final String SCHEMA_EXTENSION = "io.cryostat.graphql.SchemaExtension";
    private static final String AUDIT_CLASS = "io.cryostat.audit.Audit";

    /**
     * Every JAX-RS server endpoint method must carry @PermissionsAllowed(inclusive=true)
     * or @PermitAll. MicroProfile REST Client interfaces (@RegisterRestClient) are excluded because
     * they use JAX-RS annotations to describe outbound calls, not inbound resources.
     */
    @ArchTest
    static final ArchRule REST_ENDPOINTS_HAVE_PERMISSION_ANNOTATION =
            methods()
                    .that(isHttpMethodOnConcreteNonClientClass())
                    .should(haveRestPermissionAnnotation())
                    .as(
                            "REST endpoint methods should be annotated with"
                                    + " @PermissionsAllowed(inclusive=true) or @PermitAll");

    /**
     * Every @Query or @Mutation method in a @GraphQLApi class must carry @RequiresPermission.
     * SchemaExtension is excluded — it contains only a CDI schema-builder observer, not a resolver.
     */
    @ArchTest
    static final ArchRule GRAPHQL_QUERY_MUTATION_METHODS_HAVE_REQUIRES_PERMISSION =
            methods()
                    .that(isQueryOrMutationMethod())
                    .and()
                    .areDeclaredInClassesThat()
                    .areAnnotatedWith(GraphQLApi.class)
                    .and()
                    .areDeclaredInClassesThat()
                    .doNotHaveFullyQualifiedName(SCHEMA_EXTENSION)
                    .should()
                    .beAnnotatedWith(RequiresPermission.class)
                    .as(
                            "GraphQL @Query and @Mutation methods in @GraphQLApi classes should be"
                                    + " annotated with @RequiresPermission");

    /**
     * Every @Source resolver method in a @GraphQLApi class must carry @RequiresPermission.
     * SchemaExtension is excluded.
     */
    @ArchTest
    static final ArchRule GRAPHQL_SOURCE_METHODS_HAVE_REQUIRES_PERMISSION =
            methods()
                    .that(haveSourceAnnotatedParameterPredicate())
                    .and()
                    .areDeclaredInClassesThat()
                    .areAnnotatedWith(GraphQLApi.class)
                    .and()
                    .areDeclaredInClassesThat()
                    .doNotHaveFullyQualifiedName(SCHEMA_EXTENSION)
                    .should()
                    .beAnnotatedWith(RequiresPermission.class)
                    .as(
                            "GraphQL @Source resolver methods in @GraphQLApi classes should be"
                                    + " annotated with @RequiresPermission");

    /**
     * The audit log endpoints expose history for every tracked entity type. They must
     * declare @PermissionsAllowed(inclusive=true) covering every distinct {@code resource:read}
     * permission string found anywhere in the codebase, so that access to the audit log requires
     * the same permissions as reading the underlying resources directly.
     *
     * <p>The full set of read permissions is derived at test time by scanning all
     * {@code @PermissionsAllowed} annotations in the compiled sources, ensuring this rule stays
     * accurate as new resource types are introduced.
     */
    @Test
    void auditLogEndpointsDeclareAllReadPermissions() {
        JavaClasses classes =
                new ClassFileImporter()
                        .withImportOption(new ImportOption.DoNotIncludeTests())
                        .importPackages("io.cryostat");

        Set<String> allReadPermissions =
                classes.stream()
                        .filter(c -> !c.getFullName().equals(AUDIT_CLASS))
                        .flatMap(c -> c.getMethods().stream())
                        .flatMap(m -> m.getAnnotations().stream())
                        .filter(a -> a.getRawType().isEquivalentTo(PermissionsAllowed.class))
                        .flatMap(
                                a -> {
                                    Object val =
                                            a.get("value")
                                                    .orElseThrow(
                                                            () ->
                                                                    new IllegalStateException(
                                                                            "@PermissionsAllowed"
                                                                                    + " missing"
                                                                                    + " value"));
                                    return Arrays.stream((String[]) val);
                                })
                        .filter(p -> p.endsWith(":read"))
                        .collect(Collectors.toSet());

        assertFalse(
                allReadPermissions.isEmpty(),
                "Expected to find at least one :read permission in the codebase");

        var auditClass = classes.get(AUDIT_CLASS);
        var auditEndpointNames = Set.of("getRevisions", "exportRevisions", "getRevisionDetail");

        for (var method : auditClass.getMethods()) {
            if (!auditEndpointNames.contains(method.getName())) {
                continue;
            }
            Set<String> declared =
                    method.getAnnotations().stream()
                            .filter(a -> a.getRawType().isEquivalentTo(PermissionsAllowed.class))
                            .flatMap(
                                    a -> {
                                        Object val =
                                                a.get("value")
                                                        .orElseThrow(
                                                                () ->
                                                                        new IllegalStateException(
                                                                                "missing value"));
                                        return Arrays.stream((String[]) val);
                                    })
                            .collect(Collectors.toSet());

            Set<String> missing =
                    allReadPermissions.stream()
                            .filter(p -> !declared.contains(p))
                            .collect(Collectors.toSet());
            assertTrue(
                    missing.isEmpty(),
                    String.format(
                            "Audit endpoint %s is missing :read permissions: %s",
                            method.getFullName(),
                            missing.stream().sorted().collect(Collectors.joining(", "))));
        }
    }

    private static DescribedPredicate<JavaMethod> isHttpMethodOnConcreteNonClientClass() {
        return new DescribedPredicate<>(
                "declared in a concrete non-REST-client class and annotated with an HTTP method") {
            @Override
            public boolean test(JavaMethod method) {
                var owner = method.getOwner();
                if (owner.isInterface()) {
                    return false;
                }
                if (owner.isAnnotatedWith(RegisterRestClient.class)) {
                    return false;
                }
                return method.isAnnotatedWith(GET.class)
                        || method.isAnnotatedWith(POST.class)
                        || method.isAnnotatedWith(PUT.class)
                        || method.isAnnotatedWith(DELETE.class)
                        || method.isAnnotatedWith(PATCH.class);
            }
        };
    }

    private static ArchCondition<JavaMethod> haveRestPermissionAnnotation() {
        return new ArchCondition<>(
                "be annotated with @PermissionsAllowed(inclusive=true) or @PermitAll") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                if (method.isAnnotatedWith(PermitAll.class)) {
                    return;
                }
                if (method.isAnnotatedWith(PermissionsAllowed.class)) {
                    PermissionsAllowed annotation =
                            method.getAnnotationOfType(PermissionsAllowed.class);
                    if (annotation.inclusive()) {
                        return;
                    }
                    events.add(
                            SimpleConditionEvent.violated(
                                    method,
                                    String.format(
                                            "Method %s is annotated with @PermissionsAllowed but"
                                                    + " inclusive=true is not set",
                                            method.getFullName())));
                    return;
                }
                events.add(
                        SimpleConditionEvent.violated(
                                method,
                                String.format(
                                        "Method %s is missing @PermissionsAllowed(inclusive=true)"
                                                + " or @PermitAll",
                                        method.getFullName())));
            }
        };
    }

    private static DescribedPredicate<JavaMethod> isQueryOrMutationMethod() {
        return DescribedPredicate.<JavaMethod>describe(
                        "annotated with @Query or @Mutation",
                        m ->
                                CanBeAnnotated.Predicates.annotatedWith(Query.class).test(m)
                                        || CanBeAnnotated.Predicates.annotatedWith(Mutation.class)
                                                .test(m))
                .as("annotated with @Query or @Mutation");
    }

    private static DescribedPredicate<JavaMethod> haveSourceAnnotatedParameterPredicate() {
        return new DescribedPredicate<>("have a @Source-annotated parameter") {
            @Override
            public boolean test(JavaMethod method) {
                return method.getParameterAnnotations().stream()
                        .anyMatch(
                                paramAnnotations ->
                                        paramAnnotations.stream()
                                                .anyMatch(
                                                        a ->
                                                                a.getRawType()
                                                                        .isEquivalentTo(
                                                                                Source.class)));
            }
        };
    }
}
