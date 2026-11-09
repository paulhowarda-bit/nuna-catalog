FROM flink:2.3.0-scala_2.12-java21
# Iceberg 1.12.0's newest Flink runtime is for Flink 2.3 (F9). The Iceberg Flink catalog builds a Hadoop
# Configuration, so the shaded Hadoop client goes on the classpath too; the AWS bundle gives S3FileIO.
ADD --chown=flink:flink https://repo1.maven.org/maven2/org/apache/iceberg/iceberg-flink-runtime-2.3/1.12.0/iceberg-flink-runtime-2.3-1.12.0.jar /opt/flink/lib/
ADD --chown=flink:flink https://repo1.maven.org/maven2/org/apache/iceberg/iceberg-aws-bundle/1.12.0/iceberg-aws-bundle-1.12.0.jar /opt/flink/lib/
ADD --chown=flink:flink https://repo1.maven.org/maven2/org/apache/hadoop/hadoop-client-api/3.4.3/hadoop-client-api-3.4.3.jar /opt/flink/lib/
ADD --chown=flink:flink https://repo1.maven.org/maven2/org/apache/hadoop/hadoop-client-runtime/3.4.3/hadoop-client-runtime-3.4.3.jar /opt/flink/lib/
# A local session cluster (one job manager, one task manager) inside the container, sized for the shared laptop.
RUN sed -i 's/size: 1728m/size: 1024m/; s/size: 1600m/size: 768m/; s/numberOfTaskSlots: 1/numberOfTaskSlots: 2/' /opt/flink/conf/config.yaml
