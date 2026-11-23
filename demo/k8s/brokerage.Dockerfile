# The brokerage world's generator (D48, demo-datagen) for the demo's bootstrap Job (brokerage-bootstrap.yaml) and
# its Deployment (brokerage.yaml). Context: a folder holding lib/, the module's jar and its runtime classpath from
# ./gradlew :demo-datagen:imageLib (demo.sh fills it).
FROM eclipse-temurin:25.0.5_8-jre-noble
COPY lib/ /app/lib/
# Not root. A fixed id, so Kubernetes can check runAsNonRoot.
USER 10001:10001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-cp", "/app/lib/*", "nuna.datagen.Main"]
