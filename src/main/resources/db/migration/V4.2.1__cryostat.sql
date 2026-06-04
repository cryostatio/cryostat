-- Update username constraint in REVINFO table from < 64 to < 255
ALTER TABLE REVINFO DROP CONSTRAINT IF EXISTS revinfo_username_check;
ALTER TABLE REVINFO ADD CONSTRAINT revinfo_username_check CHECK (char_length(username) < 255);

-- Update eventType constraint in AsyncProfilerRecording table from < 50 to < 1024
ALTER TABLE AsyncProfilerRecording DROP CONSTRAINT IF EXISTS asyncprofilerrecording_eventtype_check;
ALTER TABLE AsyncProfilerRecording ADD CONSTRAINT asyncprofilerrecording_eventtype_check CHECK (char_length(eventType) < 1024);

-- Update eventType constraint in AsyncProfilerRecording_AUD table from < 50 to < 1024
ALTER TABLE AsyncProfilerRecording_AUD DROP CONSTRAINT IF EXISTS asyncprofilerrecording_aud_eventtype_check;
ALTER TABLE AsyncProfilerRecording_AUD ADD CONSTRAINT asyncprofilerrecording_aud_eventtype_check CHECK (char_length(eventType) < 1024);

-- Deduplicate Namespace DiscoveryNode instances and re-parent to KubernetesApi Realm
-- This migration fixes two issues:
-- 1. Multiple Namespace nodes with the same name were created, causing GraphQL queries to return incomplete results
-- 2. Namespace nodes were incorrectly parented to Agent Realms instead of KubernetesApi Realm,
--    causing all targets to be lost when that Agent went offline

DO $$
DECLARE
    dup_record RECORD;
    keep_node_id BIGINT;
    delete_node_id BIGINT;
    k8s_realm_id BIGINT;
BEGIN
    -- Find the KubernetesApi Realm ID
    SELECT id INTO k8s_realm_id
    FROM DiscoveryNode
    WHERE nodeType = 'Realm' AND name = 'KubernetesApi';

    IF k8s_realm_id IS NULL THEN
        RAISE EXCEPTION 'KubernetesApi Realm not found';
    END IF;

    -- Loop through each namespace name that has duplicates
    FOR dup_record IN 
        SELECT name, MIN(id) as keep_id, ARRAY_AGG(id ORDER BY id) as all_ids
        FROM DiscoveryNode
        WHERE nodeType = 'Namespace'
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
                
                RAISE NOTICE 'Deleted duplicate Namespace node % (kept node %)', delete_node_id, keep_node_id;
            END IF;
        END LOOP;
    END LOOP;

    -- Re-parent all Namespace nodes to KubernetesApi Realm
    -- This ensures k8s lineage is owned by KubernetesApi, not Agent Realms
    UPDATE DiscoveryNode
    SET parentNode = k8s_realm_id
    WHERE nodeType = 'Namespace'
      AND (parentNode IS NULL OR parentNode != k8s_realm_id);

    RAISE NOTICE 'Re-parented all Namespace nodes to KubernetesApi Realm (id=%)', k8s_realm_id;
END $$;
