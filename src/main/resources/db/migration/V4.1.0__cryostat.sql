alter table Rule add column metadata jsonb default '{"labels":{}}';

alter sequence DiscoveryNode_SEQ increment by 50;

create unique index on Rule (name);

create unique index on Target (jvmId);
create unique index on Target (connectUrl);
