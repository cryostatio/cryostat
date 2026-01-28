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
import java.util.Stack;

import io.cryostat.discovery.DiscoveryNode;
import io.cryostat.targets.Target;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
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
                            .forRevisionsOfEntity(Target.class, true, false)
                            .add(AuditEntity.property("jvmId").eq(jvmId))
                            .addOrder(AuditEntity.revisionNumber().desc())
                            .setMaxResults(1)
                            .getResultList();
            if (q.isEmpty()) {
                throw new NotFoundException();
            }
            var t = (Target) q.get(0);
            return lineage(t);
        } catch (IllegalStateException e) {
            logger.debug("Audit service not available", e);
            throw new NotFoundException();
        }
    }

    DiscoveryNode lineage(Target target) {
        Stack<DiscoveryNode> nodes = new Stack<>();
        DiscoveryNode node = target.discoveryNode;
        logger.tracev("Initial target node: {0}", node);
        while (true) {
            if (node == null) {
                break;
            }
            node.children = new ArrayList<>();
            nodes.add(node);
            logger.tracev("stack: {0}", nodes);
            logger.tracev("{0} <- {1}", node, node.parent);
            node = node.parent;
        }
        logger.tracev("final stack: {0}", nodes);

        DiscoveryNode parent = nodes.pop();
        DiscoveryNode root = parent;
        while (!nodes.isEmpty()) {
            DiscoveryNode child = nodes.pop();
            parent.children.add(child);
            parent = child;
        }
        DiscoveryNode universe = DiscoveryNode.getUniverse();
        universe.children.clear();
        universe.children.add(root);
        return universe;
    }
}
