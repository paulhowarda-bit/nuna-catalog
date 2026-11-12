# The catalog server image the Helm chart (deploy/helm/fidice-catalog) runs: the exec jar from
# ./mvnw package on a JDK 25 JRE. Build from this directory after packaging:
#   docker build -t fidice/catalog-server:0.1.0-SNAPSHOT catalog-server
FROM eclipse-temurin:25.0.5_8-jre-noble
COPY target/catalog-server-*-exec.jar /app/catalog.jar
EXPOSE 8181
ENTRYPOINT ["java", "-jar", "/app/catalog.jar"]
