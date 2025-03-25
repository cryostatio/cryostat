alter table Target
add column deleted boolean default false;

delete from DiscoveryNode where nodeType not in ('Universe', 'Realm');

delete from Target where true;

alter table Target drop constraint FKl0dhd7qeayg54dcoblpww6x34;
alter table Target drop constraint target_connecturl_key;
