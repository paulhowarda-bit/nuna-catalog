FROM apache/spark:4.1.4-scala2.13-java21-python3-ubuntu
# Iceberg 1.12.0's newest Spark runtime is for Spark 4.1 (F9); the AWS bundle gives S3FileIO.
ADD --chown=spark:spark https://repo1.maven.org/maven2/org/apache/iceberg/iceberg-spark-runtime-4.1_2.13/1.12.0/iceberg-spark-runtime-4.1_2.13-1.12.0.jar /opt/spark/jars/
ADD --chown=spark:spark https://repo1.maven.org/maven2/org/apache/iceberg/iceberg-aws-bundle/1.12.0/iceberg-aws-bundle-1.12.0.jar /opt/spark/jars/
