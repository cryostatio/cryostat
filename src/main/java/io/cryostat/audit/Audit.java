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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import io.cryostat.credentials.Credential;
import io.cryostat.diagnostic.GarbageCollection;
import io.cryostat.discovery.DiscoveryNode;
import io.cryostat.discovery.DiscoveryPlugin;
import io.cryostat.expressions.MatchExpression;
import io.cryostat.recordings.ActiveRecording;
import io.cryostat.rules.Rule;
import io.cryostat.targets.Target;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import jakarta.ws.rs.core.Response;
import org.apache.commons.lang3.StringUtils;
import org.hibernate.envers.AuditReader;
import org.hibernate.envers.AuditReaderFactory;
import org.hibernate.envers.RevisionType;
import org.hibernate.envers.exception.RevisionDoesNotExistException;
import org.hibernate.envers.query.AuditEntity;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestPath;

@Path("/api/beta/audit/")
public class Audit {

    @Inject EntityManager em;
    @Inject ObjectMapper mapper;
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
        if (page < 0) {
            throw new BadRequestException("Page number must be >= 0");
        }
        if (pageSize <= 0) {
            throw new BadRequestException("Page size must be > 0");
        }
        if (pageSize > 100) {
            throw new BadRequestException("Page size must be <= 100");
        }
        if (startTime != null && endTime != null && endTime < startTime) {
            throw new BadRequestException("End time must be >= start time");
        }

        try {
            String dataQueryName;
            String countQueryName;

            if (startTime != null && endTime != null) {
                dataQueryName = "RevisionInfo.findByTimeRange";
                countQueryName = "RevisionInfo.countByTimeRange";
            } else if (startTime != null) {
                dataQueryName = "RevisionInfo.findByStartTime";
                countQueryName = "RevisionInfo.countByStartTime";
            } else if (endTime != null) {
                dataQueryName = "RevisionInfo.findByEndTime";
                countQueryName = "RevisionInfo.countByEndTime";
            } else {
                dataQueryName = "RevisionInfo.findAll";
                countQueryName = "RevisionInfo.countAll";
            }

            var countQuery = em.createNamedQuery(countQueryName);
            if (startTime != null) {
                countQuery.setParameter("startTime", startTime);
            }
            if (endTime != null) {
                countQuery.setParameter("endTime", endTime);
            }
            long totalCount = ((Number) countQuery.getSingleResult()).longValue();

            var dataQuery = em.createNamedQuery(dataQueryName);
            if (startTime != null) {
                dataQuery.setParameter("startTime", startTime);
            }
            if (endTime != null) {
                dataQuery.setParameter("endTime", endTime);
            }
            dataQuery.setFirstResult(page * pageSize);
            dataQuery.setMaxResults(pageSize);

            @SuppressWarnings("unchecked")
            List<Object[]> results = dataQuery.getResultList();

            List<RevisionSummary> revisions = new ArrayList<>();
            for (Object[] row : results) {
                long rev = ((Number) row[0]).longValue();
                long revtstmp = ((Number) row[1]).longValue();
                String username = row[2] != null ? (String) row[2] : null;
                revisions.add(new RevisionSummary(rev, revtstmp, username));
            }

            return new RevisionsResponse(revisions, totalCount);
        } catch (IllegalStateException e) {
            logger.debug("Audit service not available", e);
            throw new NotFoundException();
        }
    }

    @GET
    @Path("export")
    @Produces(MediaType.APPLICATION_JSON)
    public Response exportRevisions(
            @QueryParam("startTime") Long startTime, @QueryParam("endTime") Long endTime) {
        if (startTime == null) {
            throw new BadRequestException("startTime query parameter is required");
        }
        if (endTime == null) {
            throw new BadRequestException("endTime query parameter is required");
        }
        if (endTime < startTime) {
            throw new BadRequestException("End time must be >= start time");
        }

        try {
            var dataQuery = em.createNamedQuery("RevisionInfo.findByTimeRange");
            dataQuery.setParameter("startTime", startTime);
            dataQuery.setParameter("endTime", endTime);

            @SuppressWarnings("unchecked")
            List<Object[]> results = dataQuery.getResultList();

            List<RevisionDetail> revisionDetails = new ArrayList<>();
            AuditReader auditReader = AuditReaderFactory.get(em);

            for (Object[] row : results) {
                long rev = ((Number) row[0]).longValue();
                RevisionDetail detail = getRevisionDetailInternal(auditReader, rev);
                if (detail != null) {
                    revisionDetails.add(detail);
                }
            }

            String filename = String.format("audit-export-%d-%d.json", startTime, endTime);
            return Response.ok(revisionDetails)
                    .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                    .build();
        } catch (IllegalStateException e) {
            logger.debug("Audit service not available", e);
            throw new NotFoundException();
        }
    }

    @GET
    @Path("revisions/{rev}")
    @Produces(MediaType.APPLICATION_JSON)
    public RevisionDetail getRevisionDetail(@RestPath long rev) {
        try {
            AuditReader auditReader = AuditReaderFactory.get(em);
            RevisionDetail detail = getRevisionDetailInternal(auditReader, rev);
            if (detail == null) {
                throw new NotFoundException();
            }
            return detail;
        } catch (IllegalStateException e) {
            logger.debug("Audit service not available", e);
            throw new NotFoundException();
        }
    }

    private RevisionDetail getRevisionDetailInternal(AuditReader auditReader, long rev) {
        RevisionInfo revisionInfo;
        try {
            revisionInfo = auditReader.findRevision(RevisionInfo.class, rev);
        } catch (RevisionDoesNotExistException e) {
            return null;
        }

        if (revisionInfo == null) {
            return null;
        }

        try {
            Map<String, List<Object>> entitiesByType = new HashMap<>();

            Class<?>[] auditedClasses = {
                Target.class,
                Rule.class,
                ActiveRecording.class,
                MatchExpression.class,
                DiscoveryPlugin.class,
                DiscoveryNode.class,
                Credential.class,
                GarbageCollection.class,
                io.cryostat.diagnostic.ThreadDump.class,
                io.cryostat.diagnostic.HeapDump.class,
                io.cryostat.events.EventTemplate.class,
                io.cryostat.recordings.ArchivedRecordingInfo.class,
                io.cryostat.jmcagent.ProbeTemplate.class
            };

            for (Class<?> entityClass : auditedClasses) {
                @SuppressWarnings("unchecked")
                List<Object> results =
                        auditReader
                                .createQuery()
                                .forRevisionsOfEntity(entityClass, false, true)
                                .add(AuditEntity.revisionNumber().eq(rev))
                                .getResultList();

                if (!results.isEmpty()) {
                    // Convert entities to Maps to avoid LazyInitializationException
                    // when serializing entities with @NotAudited lazy relationships
                    List<Object> simplifiedEntities = new ArrayList<>();
                    for (Object result : results) {
                        Object entity = null;
                        try {
                            Object[] resultArray = (Object[]) result;
                            entity = resultArray[0];
                            RevisionType revisionType = (RevisionType) resultArray[2];

                            @SuppressWarnings("unchecked")
                            Map<String, Object> entityMap =
                                    mapper.convertValue(entity, LinkedHashMap.class);
                            entityMap.put("revtype", revisionType.getRepresentation());
                            simplifiedEntities.add(entityMap);
                        } catch (IllegalArgumentException e) {
                            logger.debugv(
                                    e,
                                    "Failed to convert entity {0} to map, skipping",
                                    entity != null ? entity.getClass().getSimpleName() : "unknown");
                        } catch (ClassCastException e) {
                            logger.debugv(
                                    e,
                                    "Failed to extract revision type for entity {0}, skipping",
                                    entityClass.getSimpleName());
                        }
                    }
                    if (!simplifiedEntities.isEmpty()) {
                        entitiesByType.put(entityClass.getSimpleName(), simplifiedEntities);
                    }
                }
            }

            return new RevisionDetail(
                    revisionInfo.getId(),
                    revisionInfo.getTimestamp(),
                    revisionInfo.getUsername(),
                    entitiesByType);
        } catch (Exception e) {
            logger.debugv(e, "Failed to get revision detail for revision {0}", rev);
            return null;
        }
    }
}
