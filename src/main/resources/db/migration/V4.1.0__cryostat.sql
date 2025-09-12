/* performance optimizations */
create index on Target (jvmId);
create index on Target (connectUrl);
create index on Rule (name);

/* add Automated Rule metadata labels column */
alter table Rule add column metadata jsonb default '{"labels":{}}';

/* switch DiscoveryNode IDs from numeric-sequential to UUID */
truncate Target restart identity cascade;
truncate DiscoveryPlugin restart identity cascade;
truncate DiscoveryNode restart identity cascade;

alter table DiscoveryPlugin
    drop constraint FK81w40s7947qra1cgikpbx55mg restrict;
alter table DiscoveryNode
    drop constraint FKhercarglk8snpmw10it6wk6ri restrict;
alter table Target
    drop constraint FKl0dhd7qeayg54dcoblpww6x34 restrict;

drop sequence DiscoveryNode_SEQ cascade;
create index on DiscoveryNode (nodeType);
create index on DiscoveryNode (nodeType, name);
alter table DiscoveryNode
    alter column id drop default,
    alter column id type uuid using (gen_random_uuid()),
    alter column id set default gen_random_uuid(),
    alter column parentNode type uuid using (gen_random_uuid()),
    add constraint FKhercarglk8snpmw10it6wk6ri 
       foreign key (parentNode) 
       references DiscoveryNode;

alter table DiscoveryPlugin 
    alter column realm_id type uuid using (gen_random_uuid()),
    add constraint FK81w40s7947qra1cgikpbx55mg 
       foreign key (realm_id) 
       references DiscoveryNode;
alter table Target 
    alter column discoveryNode type uuid using (gen_random_uuid()),
    add constraint FKl0dhd7qeayg54dcoblpww6x34 
       foreign key (discoveryNode) 
       references DiscoveryNode;

/* re-establish built-in discovery plugins */
/* Insert the Universe node first explicitly to ensure it gets the first ID in sequence */
insert into DiscoveryNode(
    id,
    labels,
    name,
    nodeType,
    parentNode
) values(gen_random_uuid(), '{}'::jsonb, 'Universe', 'Universe', null);

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
(gen_random_uuid(), '{}'::jsonb, 'Custom Targets', 'Realm', (select id from universe)),
(gen_random_uuid(), '{}'::jsonb, 'KubernetesApi', 'Realm', (select id from universe)),
(gen_random_uuid(), '{}'::jsonb, 'JDP', 'Realm', (select id from universe)),
(gen_random_uuid(), '{}'::jsonb, 'Podman', 'Realm', (select id from universe)),
(gen_random_uuid(), '{}'::jsonb, 'Docker', 'Realm', (select id from universe));

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
