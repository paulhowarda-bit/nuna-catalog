# Kafka Connect 4.2.2 (the stack's broker version) for the demo's CDC leg (cdc.yaml): Debezium 3.7.1's
# PostgreSQL and Oracle source connectors, and the Apache Iceberg 1.12.0 sink with its transforms, each in a
# plugin directory of its own. The first stage resolves each plugin from demo/cdc/plugins/<name>/pom.xml, the
# build context. Kafka's own jars stay out of the plugins: Debezium brings kafka-clients 4.3.1 transitively,
# and the worker provides Kafka's classes to every plugin anyway.
FROM maven:3.9.11-eclipse-temurin-21 AS build
COPY . /build/
RUN for plugin in iceberg debezium-postgres debezium-oracle; do \
      mvn -B -q -f "/build/$plugin/pom.xml" \
        org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies \
        -DincludeScope=runtime -DexcludeGroupIds=org.apache.kafka -DoutputDirectory="/plugins/$plugin" \
        || exit 1; \
    done

FROM apache/kafka:4.2.2
COPY --from=build /plugins /opt/connect-plugins
