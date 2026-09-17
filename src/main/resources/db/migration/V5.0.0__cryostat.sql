-- Truncate all existing tables, including audit logs, to remove all rows
TRUNCATE TABLE ActiveRecording CASCADE;
TRUNCATE TABLE ActiveRecording_AUD CASCADE;
TRUNCATE TABLE ArchivedRecording CASCADE;
TRUNCATE TABLE ArchivedRecording_AUD CASCADE;
TRUNCATE TABLE AsyncProfilerRecording CASCADE;
TRUNCATE TABLE AsyncProfilerRecording_AUD CASCADE;
TRUNCATE TABLE Credential CASCADE;
TRUNCATE TABLE Credential_AUD CASCADE;
TRUNCATE TABLE DiscoveryNode CASCADE;
TRUNCATE TABLE DiscoveryNode_AUD CASCADE;
TRUNCATE TABLE DiscoveryPlugin CASCADE;
TRUNCATE TABLE DiscoveryPlugin_AUD CASCADE;
TRUNCATE TABLE EventTemplate CASCADE;
TRUNCATE TABLE EventTemplate_AUD CASCADE;
TRUNCATE TABLE GarbageCollection CASCADE;
TRUNCATE TABLE GarbageCollection_AUD CASCADE;
TRUNCATE TABLE HeapDump CASCADE;
TRUNCATE TABLE HeapDump_AUD CASCADE;
TRUNCATE TABLE MatchExpression CASCADE;
TRUNCATE TABLE MatchExpression_AUD CASCADE;
TRUNCATE TABLE ProbeTemplate CASCADE;
TRUNCATE TABLE ProbeTemplate_AUD CASCADE;
TRUNCATE TABLE REVINFO CASCADE;
TRUNCATE TABLE Rule CASCADE;
TRUNCATE TABLE Rule_AUD CASCADE;
TRUNCATE TABLE Target CASCADE;
TRUNCATE TABLE Target_AUD CASCADE;
TRUNCATE TABLE ThreadDump CASCADE;
TRUNCATE TABLE ThreadDump_AUD CASCADE;
TRUNCATE TABLE UnifiedLog CASCADE;
TRUNCATE TABLE UnifiedLog_AUD CASCADE;
TRUNCATE TABLE QRTZ_BLOB_TRIGGERS CASCADE;
TRUNCATE TABLE QRTZ_CALENDARS CASCADE;
TRUNCATE TABLE QRTZ_CRON_TRIGGERS CASCADE;
TRUNCATE TABLE QRTZ_FIRED_TRIGGERS CASCADE;
TRUNCATE TABLE QRTZ_JOB_DETAILS CASCADE;
TRUNCATE TABLE QRTZ_LOCKS CASCADE;
TRUNCATE TABLE QRTZ_PAUSED_TRIGGER_GRPS CASCADE;
TRUNCATE TABLE QRTZ_SCHEDULER_STATE CASCADE;
TRUNCATE TABLE QRTZ_SIMPLE_TRIGGERS CASCADE;
TRUNCATE TABLE QRTZ_SIMPROP_TRIGGERS CASCADE;
TRUNCATE TABLE QRTZ_TRIGGERS CASCADE;

-- Migrate all entity tables that use numeric (long) IDs to UUIDs as per io.cryostat.PanacheUuidEntity
-- Drop constraints that reference the old BIGINT id columns
ALTER TABLE ActiveRecording DROP CONSTRAINT IF EXISTS FK2g1pb3osnf0t9g12wnqfjn2a;
ALTER TABLE ActiveRecording DROP CONSTRAINT IF EXISTS UKr8nr64n7i34ipp019xrbbbyeh;
ALTER TABLE ArchivedRecording DROP CONSTRAINT IF EXISTS fk_archivedrecording_activerecording;
ALTER TABLE AsyncProfilerRecording DROP CONSTRAINT IF EXISTS fk_asyncprofilerrecording_target;
ALTER TABLE AsyncProfilerRecording DROP CONSTRAINT IF EXISTS uk_asyncprofilerrecording_target_profileid;
ALTER TABLE Credential DROP CONSTRAINT IF EXISTS FKr2h1f9wrs2kcyfwkbtyiux4dn;
ALTER TABLE DiscoveryNode DROP CONSTRAINT IF EXISTS FKhercarglk8snpmw10it6wk6ri;
ALTER TABLE DiscoveryPlugin DROP CONSTRAINT IF EXISTS FKmxng3svpr3dcm05kfiqekc3ti;
ALTER TABLE DiscoveryPlugin DROP CONSTRAINT IF EXISTS FK81w40s7947qra1cgikpbx55mg;
ALTER TABLE GarbageCollection DROP CONSTRAINT IF EXISTS fk_garbagecollection_target;
ALTER TABLE HeapDump DROP CONSTRAINT IF EXISTS fk_heapdump_target;
ALTER TABLE HeapDump DROP CONSTRAINT IF EXISTS uk_heapdump_target_jobid;
ALTER TABLE Rule DROP CONSTRAINT IF EXISTS FKosnitp3nlbo5j05my09puf3ij;
ALTER TABLE Target DROP CONSTRAINT IF EXISTS FKl0dhd7qeayg54dcoblpww6x34;
ALTER TABLE ThreadDump DROP CONSTRAINT IF EXISTS fk_threaddump_target;
ALTER TABLE ThreadDump DROP CONSTRAINT IF EXISTS uk_threaddump_target_jobid;
ALTER TABLE UnifiedLog DROP CONSTRAINT IF EXISTS fk_log_target;
ALTER TABLE UnifiedLog DROP CONSTRAINT IF EXISTS uk_log_target;

-- Drop sequence-based defaults on id columns; these cannot be automatically cast to UUID
ALTER TABLE ArchivedRecording ALTER COLUMN id DROP DEFAULT;
ALTER TABLE AsyncProfilerRecording ALTER COLUMN id DROP DEFAULT;
ALTER TABLE EventTemplate ALTER COLUMN id DROP DEFAULT;
ALTER TABLE GarbageCollection ALTER COLUMN id DROP DEFAULT;
ALTER TABLE HeapDump ALTER COLUMN id DROP DEFAULT;
ALTER TABLE ProbeTemplate ALTER COLUMN id DROP DEFAULT;
ALTER TABLE ThreadDump ALTER COLUMN id DROP DEFAULT;
ALTER TABLE UnifiedLog ALTER COLUMN id DROP DEFAULT;

-- Change id columns from BIGINT to UUID in main tables
ALTER TABLE ActiveRecording ALTER COLUMN id TYPE UUID USING gen_random_uuid();
ALTER TABLE ArchivedRecording ALTER COLUMN id TYPE UUID USING gen_random_uuid();
ALTER TABLE AsyncProfilerRecording ALTER COLUMN id TYPE UUID USING gen_random_uuid();
ALTER TABLE Credential ALTER COLUMN id TYPE UUID USING gen_random_uuid();
ALTER TABLE DiscoveryNode ALTER COLUMN id TYPE UUID USING gen_random_uuid();
ALTER TABLE EventTemplate ALTER COLUMN id TYPE UUID USING gen_random_uuid();
ALTER TABLE GarbageCollection ALTER COLUMN id TYPE UUID USING gen_random_uuid();
ALTER TABLE HeapDump ALTER COLUMN id TYPE UUID USING gen_random_uuid();
ALTER TABLE MatchExpression ALTER COLUMN id TYPE UUID USING gen_random_uuid();
ALTER TABLE ProbeTemplate ALTER COLUMN id TYPE UUID USING gen_random_uuid();
ALTER TABLE Rule ALTER COLUMN id TYPE UUID USING gen_random_uuid();
ALTER TABLE Target ALTER COLUMN id TYPE UUID USING gen_random_uuid();
ALTER TABLE ThreadDump ALTER COLUMN id TYPE UUID USING gen_random_uuid();
ALTER TABLE UnifiedLog ALTER COLUMN id TYPE UUID USING gen_random_uuid();

-- Change foreign key columns from BIGINT to UUID in main tables
ALTER TABLE ActiveRecording ALTER COLUMN target_id TYPE UUID USING NULL;
ALTER TABLE ArchivedRecording ALTER COLUMN activeRecordingId TYPE UUID USING NULL;
ALTER TABLE AsyncProfilerRecording ALTER COLUMN target_id TYPE UUID USING NULL;
ALTER TABLE Credential ALTER COLUMN matchExpression TYPE UUID USING NULL;
ALTER TABLE DiscoveryNode ALTER COLUMN parentNode TYPE UUID USING NULL;
ALTER TABLE DiscoveryPlugin ALTER COLUMN credential_id TYPE UUID USING NULL;
ALTER TABLE DiscoveryPlugin ALTER COLUMN realm_id TYPE UUID USING NULL;
ALTER TABLE GarbageCollection ALTER COLUMN target_id TYPE UUID USING NULL;
ALTER TABLE HeapDump ALTER COLUMN target_id TYPE UUID USING NULL;
ALTER TABLE Rule ALTER COLUMN matchExpression TYPE UUID USING NULL;
ALTER TABLE Target ALTER COLUMN discoveryNode TYPE UUID USING NULL;
ALTER TABLE ThreadDump ALTER COLUMN target_id TYPE UUID USING NULL;
ALTER TABLE UnifiedLog ALTER COLUMN target_id TYPE UUID USING NULL;

-- Change id columns from BIGINT to UUID in audit tables
ALTER TABLE ActiveRecording_AUD ALTER COLUMN id TYPE UUID USING gen_random_uuid();
ALTER TABLE ArchivedRecording_AUD ALTER COLUMN id TYPE UUID USING gen_random_uuid();
ALTER TABLE AsyncProfilerRecording_AUD ALTER COLUMN id TYPE UUID USING gen_random_uuid();
ALTER TABLE Credential_AUD ALTER COLUMN id TYPE UUID USING gen_random_uuid();
ALTER TABLE DiscoveryNode_AUD ALTER COLUMN id TYPE UUID USING gen_random_uuid();
ALTER TABLE DiscoveryPlugin_AUD ALTER COLUMN id TYPE UUID USING gen_random_uuid();
ALTER TABLE EventTemplate_AUD ALTER COLUMN id TYPE UUID USING gen_random_uuid();
ALTER TABLE GarbageCollection_AUD ALTER COLUMN id TYPE UUID USING gen_random_uuid();
ALTER TABLE HeapDump_AUD ALTER COLUMN id TYPE UUID USING gen_random_uuid();
ALTER TABLE MatchExpression_AUD ALTER COLUMN id TYPE UUID USING gen_random_uuid();
ALTER TABLE ProbeTemplate_AUD ALTER COLUMN id TYPE UUID USING gen_random_uuid();
ALTER TABLE Rule_AUD ALTER COLUMN id TYPE UUID USING gen_random_uuid();
ALTER TABLE Target_AUD ALTER COLUMN id TYPE UUID USING gen_random_uuid();
ALTER TABLE ThreadDump_AUD ALTER COLUMN id TYPE UUID USING gen_random_uuid();
ALTER TABLE UnifiedLog_AUD ALTER COLUMN id TYPE UUID USING gen_random_uuid();

-- Change foreign key columns from BIGINT to UUID in audit tables
ALTER TABLE ActiveRecording_AUD ALTER COLUMN target_id TYPE UUID USING NULL;
ALTER TABLE ArchivedRecording_AUD ALTER COLUMN activeRecordingId TYPE UUID USING NULL;
ALTER TABLE AsyncProfilerRecording_AUD ALTER COLUMN target_id TYPE UUID USING NULL;
ALTER TABLE Credential_AUD ALTER COLUMN matchExpression TYPE UUID USING NULL;
ALTER TABLE DiscoveryNode_AUD ALTER COLUMN parentNode TYPE UUID USING NULL;
ALTER TABLE DiscoveryPlugin_AUD ALTER COLUMN credential_id TYPE UUID USING NULL;
ALTER TABLE DiscoveryPlugin_AUD ALTER COLUMN realm_id TYPE UUID USING NULL;
ALTER TABLE GarbageCollection_AUD ALTER COLUMN target_id TYPE UUID USING NULL;
ALTER TABLE HeapDump_AUD ALTER COLUMN target_id TYPE UUID USING NULL;
ALTER TABLE Rule_AUD ALTER COLUMN matchExpression TYPE UUID USING NULL;
ALTER TABLE Target_AUD ALTER COLUMN discoveryNode TYPE UUID USING NULL;
ALTER TABLE ThreadDump_AUD ALTER COLUMN target_id TYPE UUID USING NULL;
ALTER TABLE UnifiedLog_AUD ALTER COLUMN target_id TYPE UUID USING NULL;

-- Drop old numeric ID sequences (no longer needed for UUID generation)
DROP SEQUENCE IF EXISTS ActiveRecording_SEQ;
DROP SEQUENCE IF EXISTS ArchivedRecording_SEQ;
DROP SEQUENCE IF EXISTS AsyncProfilerRecording_SEQ;
DROP SEQUENCE IF EXISTS Credential_SEQ;
DROP SEQUENCE IF EXISTS DiscoveryNode_SEQ;
DROP SEQUENCE IF EXISTS EventTemplate_SEQ;
DROP SEQUENCE IF EXISTS GarbageCollection_SEQ;
DROP SEQUENCE IF EXISTS HeapDump_SEQ;
DROP SEQUENCE IF EXISTS MatchExpression_SEQ;
DROP SEQUENCE IF EXISTS ProbeTemplate_SEQ;
DROP SEQUENCE IF EXISTS Rule_SEQ;
DROP SEQUENCE IF EXISTS Target_SEQ;
DROP SEQUENCE IF EXISTS ThreadDump_SEQ;
DROP SEQUENCE IF EXISTS UnifiedLog_SEQ;

-- Recreate foreign key constraints with UUID columns
ALTER TABLE ActiveRecording ADD CONSTRAINT FK2g1pb3osnf0t9g12wnqfjn2a FOREIGN KEY (target_id) REFERENCES Target(id);
ALTER TABLE ActiveRecording ADD CONSTRAINT UKr8nr64n7i34ipp019xrbbbyeh UNIQUE (target_id, remoteId);
ALTER TABLE ArchivedRecording ADD CONSTRAINT fk_archivedrecording_activerecording FOREIGN KEY (activeRecordingId) REFERENCES ActiveRecording(id) ON DELETE SET NULL;
ALTER TABLE AsyncProfilerRecording ADD CONSTRAINT fk_asyncprofilerrecording_target FOREIGN KEY (target_id) REFERENCES Target(id) ON DELETE CASCADE;
ALTER TABLE AsyncProfilerRecording ADD CONSTRAINT uk_asyncprofilerrecording_target_profileid UNIQUE (target_id, profileId);
ALTER TABLE Credential ADD CONSTRAINT FKr2h1f9wrs2kcyfwkbtyiux4dn FOREIGN KEY (matchExpression) REFERENCES MatchExpression(id);
ALTER TABLE DiscoveryNode ADD CONSTRAINT FKhercarglk8snpmw10it6wk6ri FOREIGN KEY (parentNode) REFERENCES DiscoveryNode(id);
ALTER TABLE DiscoveryPlugin ADD CONSTRAINT FKmxng3svpr3dcm05kfiqekc3ti FOREIGN KEY (credential_id) REFERENCES Credential(id);
ALTER TABLE DiscoveryPlugin ADD CONSTRAINT FK81w40s7947qra1cgikpbx55mg FOREIGN KEY (realm_id) REFERENCES DiscoveryNode(id);
ALTER TABLE GarbageCollection ADD CONSTRAINT fk_garbagecollection_target FOREIGN KEY (target_id) REFERENCES Target(id) ON DELETE CASCADE;
ALTER TABLE HeapDump ADD CONSTRAINT fk_heapdump_target FOREIGN KEY (target_id) REFERENCES Target(id) ON DELETE CASCADE;
ALTER TABLE HeapDump ADD CONSTRAINT uk_heapdump_target_jobid UNIQUE (target_id, jobId);
ALTER TABLE Rule ADD CONSTRAINT FKosnitp3nlbo5j05my09puf3ij FOREIGN KEY (matchExpression) REFERENCES MatchExpression(id);
ALTER TABLE Target ADD CONSTRAINT FKl0dhd7qeayg54dcoblpww6x34 FOREIGN KEY (discoveryNode) REFERENCES DiscoveryNode(id);
ALTER TABLE ThreadDump ADD CONSTRAINT fk_threaddump_target FOREIGN KEY (target_id) REFERENCES Target(id) ON DELETE CASCADE;
ALTER TABLE ThreadDump ADD CONSTRAINT uk_threaddump_target_jobid UNIQUE (target_id, jobId);
ALTER TABLE UnifiedLog ADD CONSTRAINT fk_log_target FOREIGN KEY (target_id) REFERENCES Target(id) ON DELETE CASCADE;
ALTER TABLE UnifiedLog ADD CONSTRAINT uk_log_target UNIQUE (target_id);

-- The TRUNCATE of DiscoveryNode above also removed the built-in Universe and Realm seed rows
-- inserted by V4.0.0; re-seed them here with generated UUIDs so that DiscoveryNode.getUniverse()
-- and the built-in discovery plugins continue to resolve correctly.
INSERT INTO DiscoveryNode(id, labels, name, nodeType, parentNode)
VALUES (gen_random_uuid(), '{}'::jsonb, 'Universe', 'Universe', null);

WITH universe AS (
    SELECT id FROM DiscoveryNode WHERE nodeType = 'Universe'
)
INSERT INTO DiscoveryNode(id, labels, name, nodeType, parentNode)
VALUES
    (gen_random_uuid(), '{}'::jsonb, 'Custom Targets', 'Realm', (SELECT id FROM universe)),
    (gen_random_uuid(), '{}'::jsonb, 'KubernetesApi', 'Realm', (SELECT id FROM universe)),
    (gen_random_uuid(), '{}'::jsonb, 'JDP', 'Realm', (SELECT id FROM universe)),
    (gen_random_uuid(), '{}'::jsonb, 'Podman', 'Realm', (SELECT id FROM universe)),
    (gen_random_uuid(), '{}'::jsonb, 'Docker', 'Realm', (SELECT id FROM universe));

INSERT INTO DiscoveryPlugin(id, builtin, callback, credential_id, realm_id)
SELECT gen_random_uuid(), true, null, null, DiscoveryNode.id
FROM DiscoveryNode
WHERE nodeType = 'Realm';

-- The TRUNCATE of REVINFO/DiscoveryNode_AUD/DiscoveryPlugin_AUD above also removed the synthetic
-- seed revision that V4.2.0 created for the Universe/Realm/builtin-plugin rows (which are created
-- directly via SQL and don't go through Hibernate/Envers). Re-create it here for the freshly
-- re-seeded rows above, so the Envers Validity Strategy continues to work correctly from the start.
INSERT INTO REVINFO (REV, REVTSTMP, username) VALUES (0, 0, 'system');

INSERT INTO DiscoveryNode_AUD (id, REV, REVTYPE, REVEND, REVEND_TSTMP, name, nodeType, labels, parentNode)
SELECT id, 0, 0, NULL, NULL, name, nodeType, labels, parentNode
FROM DiscoveryNode
WHERE nodeType IN ('Universe', 'Realm');

INSERT INTO DiscoveryPlugin_AUD (id, REV, REVTYPE, REVEND, REVEND_TSTMP, realm_id, callback, credential_id, builtin)
SELECT id, 0, 0, NULL, NULL, realm_id, callback, credential_id, builtin
FROM DiscoveryPlugin
WHERE builtin = true;

SELECT setval('REVINFO_SEQ', 1, false);
