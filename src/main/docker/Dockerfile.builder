FROM registry.access.redhat.com/ubi8/openjdk-17:1.20-2.1721231681 AS builder
ARG TARGETARCH
USER root
WORKDIR /tmp/build
COPY .mvn/ .mvn
COPY pom.xml mvnw .
RUN --mount=type=cache,target=/root/.m2 ./mvnw dependency:go-offline
RUN mkdir target
COPY src/main/java src/main/java
COPY src/main/resources src/main/resources
COPY src/main/webui src/main/webui
COPY src/main/docker/include src/main/docker/include
RUN --mount=type=cache,target=/root/.m2 ./mvnw -o -B -U -Dmaven.test.skip=true -Dlicense.skip=true -Dspotless.check.skip=true -Dquarkus.container-image.build=false -Dbuild.arch=$TARGETARCH package

FROM registry.access.redhat.com/ubi8/openjdk-17-runtime:1.20-3.1721231685

ENV LANGUAGE='en_US:en'
EXPOSE 8181
USER 185
LABEL io.cryostat.component=cryostat

ENV JAVA_OPTS_APPEND="-Dquarkus.http.host=0.0.0.0 -Djava.util.logging.manager=org.jboss.logmanager.LogManager"
ENV JAVA_APP_JAR="/deployments/quarkus-run.jar"

ENTRYPOINT [ "/deployments/app/entrypoint.bash", "/opt/jboss/container/java/run/run-java.sh" ]

COPY --from=builder --chown=185 /tmp/build/src/main/docker/include/cryostat.jfc /usr/lib/jvm/jre/lib/jfr/
COPY --from=builder --chown=185 /tmp/build/src/main/docker/include/genpass.bash /deployments/app/
COPY --from=builder --chown=185 /tmp/build/src/main/docker/include/entrypoint.bash /deployments/app/
COPY --from=builder --chown=185 /tmp/build/src/main/docker/include/truststore-setup.bash /deployments/app/
COPY --from=builder --chown=185 /tmp/build/target/quarkus-app/lib/ /deployments/lib/
COPY --from=builder --chown=185 /tmp/build/target/quarkus-app/*.jar /deployments/
COPY --from=builder --chown=185 /tmp/build/target/quarkus-app/app/ /deployments/app/
COPY --from=builder --chown=185 /tmp/build/target/quarkus-app/quarkus/ /deployments/quarkus/

ENV CONF_DIR=/opt/cryostat.d
ENV SSL_TRUSTSTORE=$CONF_DIR/truststore.p12 \
    SSL_TRUSTSTORE_PASS_FILE=$CONF_DIR/truststore.pass

USER root
RUN mkdir -p $CONF_DIR \
    && chmod -R g=u $CONF_DIR \
    && chown jboss:root $CONF_DIR
USER 185

RUN /deployments/app/truststore-setup.bash
