
    create sequence ActiveRecording_SEQ start with 1 increment by 50;

    create sequence Credential_SEQ start with 1 increment by 50;

    create sequence DiscoveryNode_SEQ start with 1 increment by 1;

    create sequence MatchExpression_SEQ start with 1 increment by 50;

    create sequence Rule_SEQ start with 1 increment by 50;

    create sequence Target_SEQ start with 1 increment by 50;

    create table ActiveRecording (
        id bigint not null,
        continuous boolean not null,
        duration bigint not null,
        external boolean not null,
        maxAge bigint not null,
        maxSize bigint not null,
        metadata jsonb,
        name text check (char_length(name) < 64),
        remoteId bigint not null,
        startTime bigint not null,
        state smallint check (state between 0 and 4),
        toDisk boolean not null,
        target_id bigint,
        primary key (id),
        constraint UKr8nr64n7i34ipp019xrbbbyeh unique (target_id, remoteId)
    );

    create table Credential (
        id bigint not null,
        password bytea,
        username bytea,
        matchExpression bigint unique,
        primary key (id)
    );

    create table DiscoveryNode (
        id bigint not null,
        labels jsonb,
        name text not null check (char_length(name) < 255),
        nodeType text not null check (char_length(nodeType) < 255),
        parentNode bigint,
        primary key (id)
    );

    create table DiscoveryPlugin (
        id uuid not null,
        builtin boolean not null,
        callback text unique,
        credential_id bigint unique,
        realm_id bigint not null unique,
        primary key (id)
    );

    create table MatchExpression (
        id bigint not null,
        script text not null check (char_length(script) < 1024),
        primary key (id)
    );

    create table Rule (
        id bigint not null,
        archivalPeriodSeconds integer not null,
        description text check (char_length(description) < 1024),
        enabled boolean not null,
        eventSpecifier text not null check (char_length(eventSpecifier) < 255),
        initialDelaySeconds integer not null,
        maxAgeSeconds integer not null,
        maxSizeBytes integer not null,
        name text unique check (char_length(name) < 255),
        preservedArchives integer not null,
        matchExpression bigint unique,
        primary key (id)
    );

    create table Target (
        id bigint not null,
        alias text check (char_length(alias) < 255),
        annotations jsonb,
        connectUrl bytea unique,
        jvmId text check (char_length(jvmId) < 255),
        labels jsonb,
        discoveryNode bigint unique,
        primary key (id)
    );

    alter table if exists ActiveRecording 
       add constraint FK2g1pb3osnf0t9g12wnqfjn2a 
       foreign key (target_id) 
       references Target;

    alter table if exists Credential 
       add constraint FKr2h1f9wrs2kcyfwkbtyiux4dn 
       foreign key (matchExpression) 
       references MatchExpression;

    alter table if exists DiscoveryNode 
       add constraint FKhercarglk8snpmw10it6wk6ri 
       foreign key (parentNode) 
       references DiscoveryNode;

    alter table if exists DiscoveryPlugin 
       add constraint FKmxng3svpr3dcm05kfiqekc3ti 
       foreign key (credential_id) 
       references Credential;

    alter table if exists DiscoveryPlugin 
       add constraint FK81w40s7947qra1cgikpbx55mg 
       foreign key (realm_id) 
       references DiscoveryNode;

    alter table if exists Rule 
       add constraint FKosnitp3nlbo5j05my09puf3ij 
       foreign key (matchExpression) 
       references MatchExpression;

    alter table if exists Target 
       add constraint FKl0dhd7qeayg54dcoblpww6x34 
       foreign key (discoveryNode) 
       references DiscoveryNode;


    /* Insert the Universe node first explicitly to ensure it gets the first ID in sequence */
    insert into DiscoveryNode(
        id,
        labels,
        name,
        nodeType,
        parentNode
    ) values((select nextval('DiscoveryNode_SEQ')), '{}'::jsonb, 'Universe', 'Universe', null);

    /* Select the Universe node, then insert Realm nodes for each builtin discovery plugin with the universe as their parent */
    with universe as (
        select id from DiscoveryNode where (nodeType = 'Universe')
    )
    insert into DiscoveryNode(
        id,
        labels,
        name,
        nodeType,
        parentNode
    ) values
    ((select nextval('DiscoveryNode_SEQ')), '{}'::jsonb, 'Custom Targets', 'Realm', (select id from universe)),
    ((select nextval('DiscoveryNode_SEQ')), '{}'::jsonb, 'KubernetesEndpoints', 'Realm', (select id from universe)),
    ((select nextval('DiscoveryNode_SEQ')), '{}'::jsonb, 'KubernetesEndpointSlices', 'Realm', (select id from universe)),
    ((select nextval('DiscoveryNode_SEQ')), '{}'::jsonb, 'JDP', 'Realm', (select id from universe)),
    ((select nextval('DiscoveryNode_SEQ')), '{}'::jsonb, 'Podman', 'Realm', (select id from universe)),
    ((select nextval('DiscoveryNode_SEQ')), '{}'::jsonb, 'Docker', 'Realm', (select id from universe));

    /* For each Realm node, register a corresponding plugin */
    insert into DiscoveryPlugin(
        id,
        builtin,
        callback,
        credential_id,
        realm_id
    )
    select gen_random_uuid(), true, null, null, DiscoveryNode.id
    from DiscoveryNode
    where nodeType = 'Realm';
