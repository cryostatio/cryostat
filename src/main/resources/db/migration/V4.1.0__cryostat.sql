alter sequence DiscoveryNode_SEQ increment by 50;

alter table Target
add column deleted boolean default false;
create index on Target (jvmId);
create index on Target (connectUrl);

create index on DiscoveryNode (nodeType);
create index on DiscoveryNode (nodeType, name);

create index on Rule (name);
alter table Rule add column metadata jsonb default '{"labels":{}}';

delete from DiscoveryNode where nodeType not in ('Universe', 'Realm');
delete from Target where true;
