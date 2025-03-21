alter table Rule add column metadata jsonb default '{"labels":{}}';

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
((select nextval('DiscoveryNode_SEQ')), '{}'::jsonb, 'KubernetesEndpointSlices', 'Realm', (select id from universe));

delete from DiscoveryNode where name='KubernetesApi';
