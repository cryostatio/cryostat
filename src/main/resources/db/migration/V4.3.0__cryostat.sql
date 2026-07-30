CREATE SEQUENCE UnifiedLog_SEQ START WITH 1 INCREMENT BY 50;

CREATE TABLE UnifiedLog (
    id             BIGINT NOT NULL DEFAULT nextval('UnifiedLog_SEQ'),
    target_id      BIGINT NOT NULL,
    what           text            CHECK (char_length(what) < 255),
    decorators     text            CHECK (char_length(decorators) < 255),
    status         text   NOT NULL CHECK (char_length(status) < 20),
    enabledAt      BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_log_target UNIQUE (target_id),
    CONSTRAINT fk_log_target FOREIGN KEY (target_id)
        REFERENCES Target(id) ON DELETE CASCADE
);

CREATE TABLE UnifiedLog_AUD (
    id             BIGINT NOT NULL,
    REV            INTEGER NOT NULL,
    REVTYPE        SMALLINT,
    REVEND         INTEGER,
    REVEND_TSTMP   BIGINT,
    target_id      BIGINT,
    what           text   CHECK (char_length(what) < 255),
    decorators     text   CHECK (char_length(decorators) < 255),
    status         text   CHECK (char_length(status) < 20),
    enabledAt      BIGINT,
    PRIMARY KEY (id, REV),
    FOREIGN KEY (REV)    REFERENCES REVINFO (REV),
    FOREIGN KEY (REVEND) REFERENCES REVINFO (REV)
);

CREATE INDEX IDX_LOG_AUD_ID      ON UnifiedLog_AUD (id);
CREATE INDEX IDX_LOG_AUD_REV     ON UnifiedLog_AUD (REV);
CREATE INDEX IDX_LOG_AUD_REVTYPE ON UnifiedLog_AUD (REVTYPE);
CREATE INDEX IDX_LOG_AUD_REVEND  ON UnifiedLog_AUD (REVEND);
