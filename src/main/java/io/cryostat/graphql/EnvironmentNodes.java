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
package io.cryostat.graphql;

import java.util.ArrayList;
import java.util.List;

import io.cryostat.discovery.DiscoveryNode;
import io.cryostat.graphql.RootNode.DiscoveryNodeFilter;

import io.smallrye.graphql.api.Nullable;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.graphql.DefaultValue;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Query;
import org.hibernate.envers.AuditReader;
import org.hibernate.envers.AuditReaderFactory;
import org.hibernate.envers.query.AuditEntity;
import org.jboss.logging.Logger;

@GraphQLApi
public class EnvironmentNodes {

    @Inject EntityManager em;
    @Inject Logger logger;

    @Query("environmentNodes")
    @Description("Get all environment nodes in the discovery tree with optional filtering")
    public List<DiscoveryNode> environmentNodes(
            @Nullable DiscoveryNodeFilter filter,
            @Nullable
                    @DefaultValue("false")
                    @Description(
                            "Query historical environment nodes from audit log. This is more"
                                    + " expensive and should only be used when historical data is"
                                    + " needed. When true, a non-null filter with at least one"
                                    + " filter field set must be supplied.")
                    boolean useAuditLog) {
        if (useAuditLog) {
            if (filter == null || filter.isBlank()) {
                throw new IllegalArgumentException(
                        "A non-null filter with at least one field set is required when"
                                + " useAuditLog=true");
            }
            return queryAuditLogEnvironmentNodes().stream().filter(n -> filter.test(n)).toList();
        }
        return RootNode.recurseChildren(DiscoveryNode.getUniverse(), node -> node.target == null)
                .stream()
                .filter(n -> filter == null ? true : filter.test(n))
                .toList();
    }

    private List<DiscoveryNode> queryAuditLogEnvironmentNodes() {
        try {
            AuditReader ar = AuditReaderFactory.get(em);

            // DiscoveryNode_AUD has no target column (Target owns the FK).
            // Environment nodes are DiscoveryNode entries that never appear as the
            // discoveryNode FK on any Target in the audit log.
            @SuppressWarnings("unchecked")
            List<Number> nodeIds =
                    em.createNativeQuery(
                                    "SELECT DISTINCT id FROM DiscoveryNode_AUD WHERE id NOT IN"
                                            + " (SELECT DISTINCT discoveryNode FROM Target_AUD"
                                            + " WHERE discoveryNode IS NOT NULL)")
                            .getResultList();

            List<DiscoveryNode> historicalNodes = new ArrayList<>();
            for (Number nodeId : nodeIds) {
                try {
                    @SuppressWarnings("unchecked")
                    List<Object[]> revisions =
                            ar.createQuery()
                                    .forRevisionsOfEntity(DiscoveryNode.class, false, true)
                                    .add(AuditEntity.id().eq(nodeId.longValue()))
                                    .add(
                                            AuditEntity.revisionType()
                                                    .ne(org.hibernate.envers.RevisionType.DEL))
                                    .addOrder(AuditEntity.revisionNumber().desc())
                                    .setMaxResults(1)
                                    .getResultList();
                    if (!revisions.isEmpty()) {
                        Object[] result = revisions.get(0);
                        DiscoveryNode node = (DiscoveryNode) result[0];
                        node.parent = null;
                        node.children = new ArrayList<>();
                        historicalNodes.add(node);
                    }
                } catch (Exception e) {
                    logger.warn("Failed to get DiscoveryNode from audit for id: " + nodeId, e);
                }
            }
            return historicalNodes;
        } catch (Exception e) {
            logger.debug("Error querying audit log for environment nodes", e);
            return List.of();
        }
    }
}
