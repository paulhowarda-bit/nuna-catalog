# The catalog server's container image: the runnable jar, unpacked into Spring Boot's layers so that a
# code change re-ships only the small application layers, not the 111 libraries.
#
#   ./gradlew :catalog-server:bootJar
#   docker build -f deploy/image/Dockerfile -t fidice-catalog:<version> catalog-server/build/libs
#
# The build context is the folder holding the runnable jar (*-exec.jar); nothing else is sent to Docker.
# BASE_IMAGE is any Java 25 runtime. At work, pass the approved one from the curated registry (PLT-6).
ARG BASE_IMAGE=eclipse-temurin:25.0.5_8-jre-noble

FROM ${BASE_IMAGE} AS unpack
WORKDIR /unpack
COPY *-exec.jar catalog.jar
RUN java -Djarmode=tools -jar catalog.jar extract --layers --launcher --destination layers

FROM ${BASE_IMAGE}
WORKDIR /app
COPY --from=unpack /unpack/layers/dependencies/ ./
COPY --from=unpack /unpack/layers/spring-boot-loader/ ./
COPY --from=unpack /unpack/layers/snapshot-dependencies/ ./
COPY --from=unpack /unpack/layers/application/ ./
# Not root. A fixed id, so Kubernetes can check runAsNonRoot; the server writes only to /tmp.
USER 10001:10001
EXPOSE 8181
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
