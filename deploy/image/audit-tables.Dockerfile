# The audit table writer's container image (D23): the runnable jar, unpacked into Spring Boot's layers like the
# catalog server's (deploy/image/Dockerfile) and the maintenance worker's.
#
#   ./gradlew :catalog-audit-tables:bootJar
#   docker build -f deploy/image/audit-tables.Dockerfile -t nuna-audit-tables:<version> catalog-audit-tables/build/libs
#
# The build context is the folder holding the runnable jar (*-exec.jar); nothing else is sent to Docker.
# BASE_IMAGE is any Java 25 runtime. At work, pass the approved one from the curated registry (PLT-6).
ARG BASE_IMAGE=eclipse-temurin:25.0.5_8-jre-noble

FROM ${BASE_IMAGE} AS unpack
WORKDIR /unpack
COPY *-exec.jar audit-tables.jar
RUN java -Djarmode=tools -jar audit-tables.jar extract --layers --launcher --destination layers

FROM ${BASE_IMAGE}
WORKDIR /app
COPY --from=unpack /unpack/layers/dependencies/ ./
COPY --from=unpack /unpack/layers/spring-boot-loader/ ./
COPY --from=unpack /unpack/layers/snapshot-dependencies/ ./
COPY --from=unpack /unpack/layers/application/ ./
# Not root. A fixed id, so Kubernetes can check runAsNonRoot; the writer writes only to /tmp.
USER 10001:10001
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
