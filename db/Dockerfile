# FROM registry.redhat.io/rhel8/postgresql-15:latest
FROM docker.io/library/postgres:15

RUN ln -s /usr/local/bin/docker-entrypoint.sh /usr/local/bin/postgres-entrypoint.sh

ENTRYPOINT ["/usr/local/bin/cryostat-db-entrypoint.bash"]

COPY ./pgcrypto.sql /docker-entrypoint-initdb.d/pgcrypto.sql
COPY ./entrypoint.bash /usr/local/bin/cryostat-db-entrypoint.bash
