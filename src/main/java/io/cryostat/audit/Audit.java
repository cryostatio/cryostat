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
package io.cryostat.audit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Stack;

import io.cryostat.discovery.DiscoveryNode;
import io.cryostat.targets.Target;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.envers.AuditReader;
import org.hibernate.envers.AuditReaderFactory;
import org.hibernate.envers.query.AuditEntity;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestPath;

@Path("/api/beta/audit/")
public class Audit {

    @Inject EntityManager em;
    @Inject Logger logger;

    @GET
    @Path("targets/{jvmId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Target targetByJvmId(@RestPath String jvmId) {
        if (StringUtils.isBlank(jvmId)) {
            throw new BadRequestException();
        }
        try {
            AuditReader ar = AuditReaderFactory.get(em);
            var q =
                    ar.createQuery()
                            .forRevisionsOfEntity(Target.class, true, false)
                            .add(AuditEntity.property("jvmId").eq(jvmId))
                            .addOrder(AuditEntity.revisionNumber().desc())
                            .setMaxResults(1)
                            .getResultList();
            if (q.isEmpty()) {
                throw new NotFoundException();
            }
            var t = (Target) q.get(0);
            return t;
        } catch (IllegalStateException e) {
            logger.debug("Audit service not available", e);
            throw new NotFoundException();
        }
    }

    @GET
    @Path("target_lineage/{jvmId}")
    @Produces(MediaType.APPLICATION_JSON)
    public DiscoveryNode targetLineageByJvmId(@RestPath String jvmId) {
        if (StringUtils.isBlank(jvmId)) {
            throw new BadRequestException();
        }
        try {
            AuditReader ar = AuditReaderFactory.get(em);
            var q =
                    ar.createQuery()
                            .forRevisionsOfEntity(Target.class, true, true)
                            .add(AuditEntity.property("jvmId").eq(jvmId))
                            .addOrder(AuditEntity.revisionNumber().desc())
                            .setMaxResults(1)
                            .getResultList();
            if (q.isEmpty()) {
                throw new NotFoundException();
            }
            var t = (Target) q.get(0);
            return lineage(ar, t);
        } catch (IllegalStateException e) {
            logger.debug("Audit service not available", e);
            throw new NotFoundException();
        }
    }

    DiscoveryNode lineage(AuditReader ar, Target target) {
        Stack<DiscoveryNode> nodes = new Stack<>();
        DiscoveryNode node = target.discoveryNode;
        long leafNodeId = node.id; // Keep ID of the leaf (JVM) node

        // Walk up the parent chain, querying audit history for each parent
        while (node != null) {
            node.children = new ArrayList<>();
            nodes.add(node);

            if (node.parent == null) {
                Long parentNodeId = getParentNodeId(node);
                if (parentNodeId != null) {
                    node.parent = findNodeInAuditHistory(ar, parentNodeId);
                }
            }

            node = node.parent;
        }

        // Rebuild the tree from the stack, starting with Universe at the root
        DiscoveryNode parent = nodes.pop();
        DiscoveryNode root = parent;
        parent.target = null;
        while (!nodes.isEmpty()) {
            DiscoveryNode child = nodes.pop();
            if (child.id == leafNodeId) {
                child.target = target;
            } else {
                child.target = null;
            }
            parent.children.add(child);
            parent = child;
        }

        return root;
    }

    private Long getParentNodeId(DiscoveryNode node) {
        // Query the audit table to get the parentNode foreign key value
        // We need to get it from the same revision as the node
        try {
            var result =
                    em.createNativeQuery(
                                    "SELECT parentNode FROM DiscoveryNode_AUD WHERE id = :id AND"
                                        + " REV = (SELECT MAX(REV) FROM DiscoveryNode_AUD WHERE id"
                                        + " = :id)")
                            .setParameter("id", node.id)
                            .getSingleResult();
            return result != null ? ((Number) result).longValue() : null;
        } catch (Exception e) {
            logger.debugv(e, "Failed to get parent node ID for node {0}", node.id);
        }
        return null;
    }

    private DiscoveryNode findNodeInAuditHistory(AuditReader ar, Long nodeId) {
        try {
            var q =
                    ar.createQuery()
                            .forRevisionsOfEntity(DiscoveryNode.class, true, true)
                            .add(AuditEntity.id().eq(nodeId))
                            .addOrder(AuditEntity.revisionNumber().desc())
                            .setMaxResults(1)
                            .getResultList();
            if (!q.isEmpty()) {
                return (DiscoveryNode) q.get(0);
            }
        } catch (Exception e) {
            logger.debugv(e, "Failed to find node {0} in audit history", nodeId);
        }
        return null;
    }

    @GET
    @Path("revisions")
    @Produces(MediaType.APPLICATION_JSON)
    public RevisionsResponse getRevisions(
            @QueryParam("startTime") Long startTime,
            @QueryParam("endTime") Long endTime,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("pageSize") @DefaultValue("50") int pageSize) {
        // TODO: Implement revision query logic
        return new RevisionsResponse(Collections.emptyList(), 0L);
    }

    @GET
    @Path("revisions/{rev}")
    @Produces(MediaType.APPLICATION_JSON)
    public RevisionDetail getRevisionDetail(@RestPath int rev) {
        // TODO: Implement revision detail logic
        throw new NotFoundException();
    }
}
