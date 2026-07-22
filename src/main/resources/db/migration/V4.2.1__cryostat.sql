-- Update username constraint in REVINFO table from < 64 to < 255
ALTER TABLE REVINFO DROP CONSTRAINT IF EXISTS revinfo_username_check;
ALTER TABLE REVINFO ADD CONSTRAINT revinfo_username_check CHECK (char_length(username) < 255);

-- Update eventType constraint in AsyncProfilerRecording table from < 50 to < 1024
ALTER TABLE AsyncProfilerRecording DROP CONSTRAINT IF EXISTS asyncprofilerrecording_eventtype_check;
ALTER TABLE AsyncProfilerRecording ADD CONSTRAINT asyncprofilerrecording_eventtype_check CHECK (char_length(eventType) < 1024);

-- Update eventType constraint in AsyncProfilerRecording_AUD table from < 50 to < 1024
ALTER TABLE AsyncProfilerRecording_AUD DROP CONSTRAINT IF EXISTS asyncprofilerrecording_aud_eventtype_check;
ALTER TABLE AsyncProfilerRecording_AUD ADD CONSTRAINT asyncprofilerrecording_aud_eventtype_check CHECK (char_length(eventType) < 1024);

-- Deduplicate k8s lineage DiscoveryNode instances and re-parent to KubernetesApi Realm
-- This migration fixes issues where:
-- 1. Multiple nodes with the same name/type were created, causing GraphQL queries to return incomplete results
-- 2. K8s lineage nodes were incorrectly parented to Agent Realms instead of KubernetesApi Realm,
--    causing all targets to be lost when that Agent went offline
-- 3. Deployment, ReplicaSet, and Pod nodes were duplicated instead of being shared across replicas

DO $$
DECLARE
    dup_record RECORD;
    keep_node_id BIGINT;
    delete_node_id BIGINT;
    k8s_realm_id BIGINT;
    node_type_name TEXT;
BEGIN
    -- Find the KubernetesApi Realm ID
    SELECT id INTO k8s_realm_id
    FROM DiscoveryNode
    WHERE nodeType = 'Realm' AND name = 'KubernetesApi';

    IF k8s_realm_id IS NULL THEN
        RAISE EXCEPTION 'KubernetesApi Realm not found';
    END IF;

    -- Deduplicate k8s lineage nodes in order: Namespace, Deployment, ReplicaSet, Pod
    -- This order ensures parent nodes are deduplicated before their children
    FOREACH node_type_name IN ARRAY ARRAY['Namespace', 'Deployment', 'ReplicaSet', 'Pod']
    LOOP
        -- Loop through each node name that has duplicates of this type
        FOR dup_record IN
            SELECT name, MIN(id) as keep_id, ARRAY_AGG(id ORDER BY id) as all_ids
            FROM DiscoveryNode
            WHERE nodeType = node_type_name
            GROUP BY name
            HAVING COUNT(*) > 1
        LOOP
            keep_node_id := dup_record.keep_id;

            -- Loop through each duplicate ID (excluding the one we're keeping)
            FOREACH delete_node_id IN ARRAY dup_record.all_ids
            LOOP
                IF delete_node_id != keep_node_id THEN
                    -- Update child DiscoveryNodes to point to the kept node
                    UPDATE DiscoveryNode
                    SET parentNode = keep_node_id
                    WHERE parentNode = delete_node_id;

                    -- Update Targets to point to the kept node
                    UPDATE Target
                    SET discoveryNode = keep_node_id
                    WHERE discoveryNode = delete_node_id;

                    -- Delete the duplicate node
                    DELETE FROM DiscoveryNode WHERE id = delete_node_id;

                    RAISE NOTICE 'Deleted duplicate % node "%" (id=%, kept id=%)',
                        node_type_name, dup_record.name, delete_node_id, keep_node_id;
                END IF;
            END LOOP;
        END LOOP;
    END LOOP;

    -- Re-parent all Namespace nodes to KubernetesApi Realm
    -- This ensures k8s lineage is owned by KubernetesApi, not Agent Realms
    UPDATE DiscoveryNode
    SET parentNode = k8s_realm_id
    WHERE nodeType = 'Namespace'
      AND (parentNode IS NULL OR parentNode != k8s_realm_id);

    RAISE NOTICE 'Re-parented all Namespace nodes to KubernetesApi Realm (id=%)', k8s_realm_id;

    -- Add plugin-id label to existing CryostatAgent target nodes
    -- This enables cleanup when plugins are deregistered or pruned
    -- We identify the plugin by finding the Realm that owns the target's ancestor tree
    UPDATE DiscoveryNode target_node
    SET labels = COALESCE(labels, '{}'::jsonb) ||
                 jsonb_build_object('discovery.cryostat.io/plugin-id', plugin.id::text)
    FROM DiscoveryPlugin plugin
    WHERE target_node.nodeType = 'CryostatAgent'
      AND EXISTS (SELECT 1 FROM Target WHERE discoveryNode = target_node.id)
      AND NOT (target_node.labels ? 'discovery.cryostat.io/plugin-id')
      AND EXISTS (
          -- Find the Realm ancestor of this target node
          WITH RECURSIVE ancestor_tree AS (
              SELECT id, parentNode, nodeType
              FROM DiscoveryNode
              WHERE id = target_node.id

              UNION ALL

              SELECT dn.id, dn.parentNode, dn.nodeType
              FROM DiscoveryNode dn
              INNER JOIN ancestor_tree at ON dn.id = at.parentNode
          )
          SELECT 1
          FROM ancestor_tree
          WHERE nodeType = 'Realm'
            AND id = plugin.realm_id
      );

    RAISE NOTICE 'Added plugin-id labels to existing CryostatAgent target nodes';
END $$;

-- Migrate existing GarbageCollection rows into _AUD as INSERT+DELETE revision pairs,
-- then truncate the primary table. Going forward, the gc() handler will persist and
-- immediately delete each entity so only _AUD retains the durable record.
DO $$
DECLARE
    migration_rev   INTEGER;
    gc              RECORD;
    insert_rev      INTEGER;
    gc_count        INTEGER;
BEGIN
    SELECT COUNT(*) INTO gc_count FROM GarbageCollection;

    IF gc_count > 0 THEN
        -- Create one synthetic REVINFO row for this migration event only when there are rows
        -- to migrate. Using timestamp 0 places this revision in the same "seed" epoch as the
        -- initial revision, keeping it out of any recent time-range queries.
        -- REVINFO.REV has no DEFAULT; use nextval() explicitly (Hibernate manages the sequence).
        INSERT INTO REVINFO (REV, REVTSTMP, username)
        VALUES (nextval('REVINFO_SEQ'), 0, 'migration')
        RETURNING REV INTO migration_rev;

        -- For each existing GarbageCollection row that has a matching INSERT revision in _AUD,
        -- add a DELETE revision and back-fill REVEND on the INSERT revision.
        FOR gc IN SELECT id FROM GarbageCollection LOOP

            -- Find the INSERT revision for this entity
            SELECT REV INTO insert_rev
            FROM GarbageCollection_AUD
            WHERE id = gc.id AND REVTYPE = 0
            ORDER BY REV DESC
            LIMIT 1;

            IF insert_rev IS NOT NULL THEN
                -- Back-fill REVEND on the INSERT row (ValidityAuditStrategy requirement)
                UPDATE GarbageCollection_AUD
                SET REVEND = migration_rev, REVEND_TSTMP = 0
                WHERE id = gc.id AND REV = insert_rev;

                -- Insert the DELETE revision row
                INSERT INTO GarbageCollection_AUD (id, REV, REVTYPE, REVEND, REVEND_TSTMP, target_id, triggeredAt)
                SELECT gc.id, migration_rev, 2, NULL, NULL, target_id, triggeredAt
                FROM GarbageCollection
                WHERE id = gc.id;
            END IF;
        END LOOP;

        RAISE NOTICE 'GarbageCollection migration complete: primary table truncated, % revision created', migration_rev;
    ELSE
        RAISE NOTICE 'GarbageCollection migration: no existing rows, skipping revision creation';
    END IF;

    -- Primary table is now fully mirrored in _AUD; clear it (no-op if already empty)
    TRUNCATE TABLE GarbageCollection;
END $$;
