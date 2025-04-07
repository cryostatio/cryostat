alter table Rule add column metadata jsonb default '{"labels":{}}';

alter sequence DiscoveryNode_SEQ increment by 50;

create index on Rule (name);

create index on Target (jvmId);
create index on Target (connectUrl);

create index on DiscoveryNode (nodeType);
create unique index on DiscoveryNode (nodeType, name);
alter table DiscoveryNode add constraint uniqueNodeTypeName unique (nodeType, name);
