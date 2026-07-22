CREATE SEQUENCE GcLog_SEQ START WITH 1 INCREMENT BY 50;

CREATE TABLE GcLog (
    id             BIGINT NOT NULL DEFAULT nextval('GcLog_SEQ'),
    target_id      BIGINT NOT NULL,
    what           text            CHECK (char_length(what) < 255),
    decorators     text            CHECK (char_length(decorators) < 255),
    filename       text            CHECK (char_length(filename) < 255),
    status         text   NOT NULL CHECK (char_length(status) < 20),
    enabledAt      BIGINT NOT NULL,
    lastModifiedAt BIGINT,
    size           BIGINT,
    PRIMARY KEY (id),
    CONSTRAINT uk_gclog_target UNIQUE (target_id),
    CONSTRAINT fk_gclog_target FOREIGN KEY (target_id)
        REFERENCES Target(id) ON DELETE CASCADE
);

CREATE TABLE GcLog_AUD (
    id             BIGINT NOT NULL,
    REV            INTEGER NOT NULL,
    REVTYPE        SMALLINT,
    REVEND         INTEGER,
    REVEND_TSTMP   BIGINT,
    target_id      BIGINT,
    what           text   CHECK (char_length(what) < 255),
    decorators     text   CHECK (char_length(decorators) < 255),
    filename       text   CHECK (char_length(filename) < 255),
    status         text   CHECK (char_length(status) < 20),
    enabledAt      BIGINT,
    lastModifiedAt BIGINT,
    size           BIGINT,
    PRIMARY KEY (id, REV),
    FOREIGN KEY (REV)    REFERENCES REVINFO (REV),
    FOREIGN KEY (REVEND) REFERENCES REVINFO (REV)
);

CREATE INDEX IDX_GCLOG_AUD_ID      ON GcLog_AUD (id);
CREATE INDEX IDX_GCLOG_AUD_REV     ON GcLog_AUD (REV);
CREATE INDEX IDX_GCLOG_AUD_REVTYPE ON GcLog_AUD (REVTYPE);
CREATE INDEX IDX_GCLOG_AUD_REVEND  ON GcLog_AUD (REVEND);
