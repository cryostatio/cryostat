-- Update username constraint in REVINFO table from < 64 to < 255
ALTER TABLE REVINFO DROP CONSTRAINT IF EXISTS revinfo_username_check;
ALTER TABLE REVINFO ADD CONSTRAINT revinfo_username_check CHECK (char_length(username) < 255);

-- Update eventType constraint in AsyncProfilerRecording table from < 50 to < 1024
ALTER TABLE AsyncProfilerRecording DROP CONSTRAINT IF EXISTS asyncprofilerrecording_eventtype_check;
ALTER TABLE AsyncProfilerRecording ADD CONSTRAINT asyncprofilerrecording_eventtype_check CHECK (char_length(eventType) < 1024);

-- Update eventType constraint in AsyncProfilerRecording_AUD table from < 50 to < 1024
ALTER TABLE AsyncProfilerRecording_AUD DROP CONSTRAINT IF EXISTS asyncprofilerrecording_aud_eventtype_check;
ALTER TABLE AsyncProfilerRecording_AUD ADD CONSTRAINT asyncprofilerrecording_aud_eventtype_check CHECK (char_length(eventType) < 1024);