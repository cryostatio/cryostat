-- Update username constraint in REVINFO table from < 64 to < 255
ALTER TABLE REVINFO DROP CONSTRAINT IF EXISTS revinfo_username_check;
ALTER TABLE REVINFO ADD CONSTRAINT revinfo_username_check CHECK (char_length(username) < 255);

-- Update eventType constraint in AsyncProfilerRecording table from < 50 to < 1024
ALTER TABLE AsyncProfilerRecording DROP CONSTRAINT IF EXISTS asyncprofilerrecording_eventtype_check;
ALTER TABLE AsyncProfilerRecording ADD CONSTRAINT asyncprofilerrecording_eventtype_check CHECK (char_length(eventType) < 1024);

-- Update eventType constraint in AsyncProfilerRecording_AUD table from < 50 to < 1024
ALTER TABLE AsyncProfilerRecording_AUD DROP CONSTRAINT IF EXISTS asyncprofilerrecording_aud_eventtype_check;
ALTER TABLE AsyncProfilerRecording_AUD ADD CONSTRAINT asyncprofilerrecording_aud_eventtype_check CHECK (char_length(eventType) < 1024);

-- Deduplicate Namespace DiscoveryNode instances
-- This migration fixes a bug where multiple Namespace nodes with the same name
-- were created in the database, causing GraphQL queries to return incomplete results.

-- For each namespace name that has duplicates, keep only the one with the lowest ID
-- and delete the rest, after updating all references to point to the kept node.

DO $$
DECLARE
    dup_record RECORD;
    keep_node_id BIGINT;
    delete_node_id BIGINT;
BEGIN
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
END $$;
