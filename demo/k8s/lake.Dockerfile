# The lake writer: demo/LakeWriter.java on the change-feed connector's Iceberg libraries.
# Context: a folder holding LakeWriter.java and lib/ (demo.sh fills it).
FROM eclipse-temurin:25.0.5_8-jdk-noble
COPY lib/ /lake/lib/
COPY LakeWriter.java /lake/
ENV JAVA_TOOL_OPTIONS=-Xmx384m
ENTRYPOINT ["java", "-cp", "/lake/lib/*", "/lake/LakeWriter.java"]
