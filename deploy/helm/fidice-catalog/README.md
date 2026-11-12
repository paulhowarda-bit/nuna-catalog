# fidice-catalog Helm chart

Deploys the catalog server: one Deployment, a ClusterIP Service on port 8181 named after the release, a
ConfigMap with the server's Spring properties and a Secret with its environment.

```
./mvnw package -pl catalog-server -am -DskipTests
docker build -t fidice/catalog-server:0.1.0-SNAPSHOT catalog-server
helm upgrade --install catalog deploy/helm/fidice-catalog -f my-values.yaml
```

- `config`: the server's properties, written to `/config/application.properties`: the database URL and user,
  storage profiles and catalogs (`fidice.storage.*`), token issuers (`fidice.security.*`), audit
  (`fidice.audit.*`). Nothing secret.
- `secretEnv`: environment variables kept in the Secret, such as `SPRING_DATASOURCE_PASSWORD` or the store's
  credentials.
- The server has no health route yet, so the probes check that port 8181 accepts connections; it listens only
  once startup (schema, storage profiles, catalogs) has succeeded.

The engine matrix (`catalog-tests-engines`) installs this chart into a kind cluster; its harness shows a
complete set of values.
