# Kafka Connect 4.2.2 (the stack's broker version) with the Apache Iceberg 1.12.0 sink. The first stage
# resolves the sink and its runtime dependencies (pom.xml) into a plugin directory and compiles Catalog.java,
# the run script's REST client: the Kafka image has neither curl nor javac.
FROM maven:3.9.11-eclipse-temurin-21 AS build
COPY pom.xml Catalog.java /build/
RUN mvn -B -q -f /build/pom.xml org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies \
      -DincludeScope=runtime -DoutputDirectory=/plugins/iceberg-sink \
 && javac --release 21 -d /tools /build/Catalog.java

FROM apache/kafka:4.2.2
COPY --from=build /plugins /opt/connect-plugins
COPY --from=build /tools /opt/tools
# The run script, baked in so a Kubernetes Job needs no volume.
COPY run.sh /scripts/
