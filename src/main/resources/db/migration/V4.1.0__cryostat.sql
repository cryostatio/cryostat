alter table Target
add column deleted boolean default false;

delete from DiscoveryNode where nodeType not in ('Universe', 'Realm');

delete from Target where true;
