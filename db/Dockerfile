# FROM registry.redhat.io/rhel8/postgresql-15:latest
FROM docker.io/library/postgres:15

ENTRYPOINT ["/usr/local/bin/cryostat-db-entrypoint.bash"]

COPY ./pgcrypto.sql /docker-entrypoint-initdb.d/pgcrypto.sql
COPY ./entrypoint.bash /usr/local/bin/cryostat-db-entrypoint.bash
