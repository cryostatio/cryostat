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

import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.graphql.execution.event.InvokeInfo;
import io.smallrye.graphql.spi.EventingService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.GraphQLException;
import org.jboss.logging.Logger;

/**
 * SmallRye GraphQL {@link EventingService} that enforces {@link RequiresPermission} annotations on
 * resolver methods before invocation.
 *
 * <p>The {@link #beforeInvoke(InvokeInfo)} hook fires just before the resolver method is called,
 * providing direct access to the {@link Method} object. If the method (or its declaring class)
 * carries {@link RequiresPermission}, each listed permission is checked against the current {@link
 * SecurityIdentity}. A failing check aborts the operation with a GraphQL {@code FORBIDDEN} error.
 *
 * <p>In {@code PERMISSIVE} mode all operations proceed without checks.
 */
@ApplicationScoped
public class GraphQLSecurityInterceptor implements EventingService {

    @Inject RbacConfig rbacConfig;
    @Inject SecurityIdentity securityIdentity;
    @Inject Logger logger;

    @Override
    public String getConfigKey() {
        // Always active; mode-based bypassing is handled inside beforeInvoke().
        return null;
    }

    @Override
    public void beforeInvoke(InvokeInfo invokeInfo) throws Exception {
        if (rbacConfig.mode() == RbacMode.PERMISSIVE) {
            return;
        }

        Method method = invokeInfo.getOperationMethod();
        List<String> required = collectRequiredPermissions(method);
        if (required.isEmpty()) {
            return;
        }

        for (String permissionName : required) {
            boolean allowed =
                    securityIdentity
                            .checkPermission(PermissionMapper.toPermission(permissionName))
                            .await()
                            .indefinitely();
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
