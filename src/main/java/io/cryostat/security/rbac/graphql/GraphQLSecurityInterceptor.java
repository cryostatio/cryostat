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
package io.cryostat.security.rbac.graphql;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.cryostat.security.rbac.PermissionMapper;
import io.cryostat.security.rbac.RbacConfig;
import io.cryostat.security.rbac.RbacMode;
import io.cryostat.security.rbac.UserAuthorizer;

import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.graphql.execution.event.InvokeInfo;
import io.smallrye.mutiny.TimeoutException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.jboss.logging.Logger;

/**
 * Enforces {@link RequiresPermission} annotations on GraphQL resolver methods before invocation.
 *
 * <p>SmallRye GraphQL fires an {@link InvokeInfo} CDI event just before each resolver method is
 * called, provided {@code quarkus.smallrye-graphql.events.enabled} is true. The event carries the
 * {@link Method} object. If the method (or its declaring class) carries {@link RequiresPermission},
 * each listed permission is checked against the current {@link SecurityIdentity}. A failing check
 * aborts the operation with a GraphQL error.
 *
 * <p>Each permission check waits at most {@link #CHECK_TIMEOUT}; a check that does not complete in
 * that time is treated as a denial.
 *
 * <p>In {@code PERMISSIVE} mode all operations proceed without checks.
 */
@ApplicationScoped
public class GraphQLSecurityInterceptor {

    @Inject RbacConfig rbacConfig;
    @Inject SecurityIdentity securityIdentity;
    @Inject Logger logger;

    void beforeInvoke(@Observes InvokeInfo invokeInfo) throws GraphQLException {
        if (rbacConfig.mode() == RbacMode.PERMISSIVE) {
            return;
        }

        Method method = invokeInfo.getOperationMethod();
        List<String> required = collectRequiredPermissions(method);
        if (required.isEmpty()) {
            return;
        }

        for (String permissionName : required) {
            boolean allowed;
            try {
                allowed =
                        securityIdentity
                                .checkPermission(PermissionMapper.toPermission(permissionName))
                                .await()
                                .atMost(UserAuthorizer.RBAC_CHECK_TIMEOUT);
            } catch (TimeoutException e) {
                logger.warnf(
                        "GraphQL permission check timed out after %s: %s for method %s, denying",
                        UserAuthorizer.RBAC_CHECK_TIMEOUT, permissionName, method.getName());
                allowed = false;
            }
            if (!allowed) {
                logger.debugf(
                        "GraphQL permission check failed: %s for method %s",
                        permissionName, method.getName());
                throw new GraphQLException(
                        "Forbidden: insufficient permissions",
                        GraphQLException.ExceptionType.ExecutionAborted);
            }
        }
    }

    private List<String> collectRequiredPermissions(Method method) {
        List<String> permissions = new ArrayList<>();
        RequiresPermission typeAnnotation =
                method.getDeclaringClass().getAnnotation(RequiresPermission.class);
        if (typeAnnotation != null) {
            permissions.addAll(List.of(typeAnnotation.value()));
        }
        RequiresPermission methodAnnotation = method.getAnnotation(RequiresPermission.class);
        if (methodAnnotation != null) {
            permissions.addAll(List.of(methodAnnotation.value()));
        }
        return permissions;
    }
}
