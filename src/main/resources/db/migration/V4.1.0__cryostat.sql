alter sequence DiscoveryNode_SEQ increment by 50;

alter table Target
add column deleted timestamp default null;
create index on Target (jvmId);
create index on Target (connectUrl);

create index on DiscoveryNode (nodeType);
create index on DiscoveryNode (nodeType, name);

create index on Rule (name);
alter table Rule add column metadata jsonb default '{"labels":{}}';

delete from DiscoveryNode where nodeType not in ('Universe', 'Realm');
delete from Target where true;

alter table Target drop constraint FKl0dhd7qeayg54dcoblpww6x34;
alter table Target drop constraint target_connecturl_key;
