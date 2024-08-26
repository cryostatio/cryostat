
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
        name varchar(255),
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
        name varchar(255) not null,
        nodeType varchar(255) not null,
        parentNode bigint,
        primary key (id)
    );

    create table DiscoveryPlugin (
        id uuid not null,
        builtin boolean not null,
        callback varchar(255) unique,
        credential_id bigint unique,
        realm_id bigint not null unique,
        primary key (id)
    );

    create table MatchExpression (
        id bigint not null,
        script varchar(255) not null,
        primary key (id)
    );

    create table Rule (
        id bigint not null,
        archivalPeriodSeconds integer not null,
        description varchar(255),
        enabled boolean not null,
        eventSpecifier varchar(255) not null,
        initialDelaySeconds integer not null,
        maxAgeSeconds integer not null,
        maxSizeBytes integer not null,
        name varchar(255) unique,
        preservedArchives integer not null,
        matchExpression bigint unique,
        primary key (id)
    );

    create table Target (
        id bigint not null,
        alias varchar(255),
        annotations jsonb,
        connectUrl bytea unique,
        jvmId varchar(255),
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
    ((select nextval('DiscoveryNode_SEQ')), '{}'::jsonb, 'KubernetesApi', 'Realm', (select id from universe)),
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
