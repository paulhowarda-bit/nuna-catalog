#!/usr/bin/env bash
# The local demo of nuna-catalog, on Docker Desktop's Kubernetes: the stack it shares with the engine matrix
# (demo/k8s), its sample data, and the matrix's engines on demand. See demo/README.md.
#
#   demo/demo.sh up       build the jars and images, start the stack, load sample data once, print tokens
#   demo/demo.sh engines  run the engine matrix (Spark, Kafka Connect, PyIceberg, SingleStore) against the stack
#   demo/demo.sh token    print the URLs and the tokens again (re-mints them if they have expired)
#   demo/demo.sh sql      open a SingleStore prompt (databases ops and lake)
#   demo/demo.sh rate     append an FX rate to Iceberg (markets.fx.rates); SingleStore loads it into lake.fx_rates
#   demo/demo.sh cdc      start the CDC leg: PostgreSQL, Oracle and CockroachDB, through Debezium and Kafka
#                         Connect, into Iceberg history tables (stops Trino while it runs)
#   demo/demo.sh cdc stop delete the CDC leg (the history tables stay) and start Trino again
#   demo/demo.sh psql [user]     a psql prompt on the CDC leg's PostgreSQL, database backoffice (default backoffice)
#   demo/demo.sh sqlplus [user]  a SQL*Plus prompt on the CDC leg's Oracle, PDB FREEPDB1 (default treasury)
#   demo/demo.sh cockroach [user]  a SQL prompt on the CDC leg's CockroachDB, database custody (default root)
#   demo/demo.sh status   show the pods and services
#   demo/demo.sh down     delete the namespace and everything in it (keeps the signing key, tokens, Keycloak keys
#                         and the certificate)
#
# Needs bash (Git Bash on Windows), Docker Desktop with Kubernetes on, kubectl, helm, curl, unzip, openssl (Git
# Bash has it), and JDK 25 (JAVA_HOME or java on PATH). kubectl and helm always use NUNA_DEMO_CONTEXT (default
# docker-desktop), never the current context, and the stack is namespace NUNA_DEMO_NAMESPACE (default
# nuna-demo). Docker Desktop's cluster runs images from the local Docker engine and serves LoadBalancer
# Services on localhost. The signing key, tokens, Keycloak's keys and the certificate are kept in
# NUNA_DEMO_HOME (default ~/.nuna-demo).
#
# Every page is HTTPS, at https://<NUNA_DEMO_HOST>:<port> (default localhost), with the certificate in
# $NUNA_DEMO_HOME/tls (publish_tls). At work, put work's certificate there and set NUNA_DEMO_HOST to its name
# (docs/install-at-work.md).
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN="${NUNA_DEMO_HOME:-$HOME/.nuna-demo}"
CONTEXT="${NUNA_DEMO_CONTEXT:-docker-desktop}"
NS="${NUNA_DEMO_NAMESPACE:-nuna-demo}"
DEMO_HOST="${NUNA_DEMO_HOST:-localhost}"
TLS="$RUN/tls"
STACK="$REPO/demo/k8s"
RELEASE=nuna-catalog
URL="https://$DEMO_HOST:8181"
# demo.sh's own calls: they trust the certificate's CA, and reach the ports on this machine whatever the name
# resolves to. Git Bash's curl checks certificates with Windows (Schannel), which fails a CA that names no
# revocation list, as the demo's own does, unless told to check revocation only where it can.
CURL_TLS=(--cacert "$TLS/ca.crt" --ssl-revoke-best-effort --connect-to "$DEMO_HOST::127.0.0.1:")
CATALOG=lakehouse
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
# SingleStore's root password, as demo/k8s/singlestore.yaml sets it. Local only.
S2_ROOT_PASSWORD=nuna-test
KAFKA_UI_IMAGE=kafbat/kafka-ui:v1.5.0
SINGLESTORE_IMAGE=
TRINO_IMAGE=trinodb/trino:484
# Signs people in to CloudBeaver through Keycloak (cloudbeaver.yaml).
OAUTH2_PROXY_IMAGE=quay.io/oauth2-proxy/oauth2-proxy:v7.15.6
CLOUDBEAVER_IMAGE=
CONNECT_IMAGE=
# Kafka Connect's REST API while the CDC leg runs (ports.yaml).
CONNECT_URL="https://$DEMO_HOST:8186"
# The front door (ports.yaml): TLS on every port the demo publishes.
NGINX_IMAGE=nginx:1.30.6-alpine
# SQL*Plus's login to the CDC leg's Oracle as user treasury (demo/cdc/oracle.sql). Local only.
ORACLE_TREASURY=treasury/\"nuna-test\"@//localhost:1521/FREEPDB1

k() { kubectl --context "$CONTEXT" -n "$NS" "$@"; }

say() { printf '\n== %s\n' "$*"; }

token_of() { tr -d '\r\n' < "$RUN/tokens/$1.jwt"; }

# Changes whenever Mint.java mints. Trino and CloudBeaver read tokens only as they start and carry this as a pod
# annotation (trino.yaml, cloudbeaver.yaml), so new tokens replace their pods.
tokens_hash() { sha256sum "$RUN/tokens/expires.txt" | cut -c1-12; }

# Sets that annotation as up would, outside up: new tokens replace both pods, and the same tokens change nothing.
annotate_tokens() {
  local deployment
  for deployment in trino cloudbeaver; do
    k patch deployment "$deployment" --type merge \
      -p "{\"spec\":{\"template\":{\"metadata\":{\"annotations\":{\"nuna-demo/tokens\":\"$(tokens_hash)\"}}}}}" >/dev/null
  done
}

# An image named in gradle.properties, the versions the builds use.
image_of() { sed -n "s/^$1=//p" "$REPO/gradle.properties" | tr -d '\r'; }

# A file from demo/k8s with the run's ${...} values put in.
fill() {
  sed -e "s|\${DEMO_HOST}|$DEMO_HOST|g" -e "s|\${NAMESPACE}|$NS|g" -e "s|\${NGINX_IMAGE}|$NGINX_IMAGE|g" \
    -e "s|\${TLS_HASH}|$(cat "$TLS/tls.crt" "$TLS/tls.key" 2>/dev/null | sha256sum | cut -c1-12)|g" \
    -e "s|\${SILO_IMAGE}|$(image_of silo.image)|g" -e "s|\${S3_ENDPOINT}|http://s3:9000|g" \
    -e "s|\${KAFKA_UI_IMAGE}|$KAFKA_UI_IMAGE|g" -e "s|\${SINGLESTORE_IMAGE}|$SINGLESTORE_IMAGE|g" \
    -e "s|\${TRINO_IMAGE}|$TRINO_IMAGE|g" -e "s|\${OAUTH2_PROXY_IMAGE}|$OAUTH2_PROXY_IMAGE|g" \
    -e "s|\${CLOUDBEAVER_IMAGE}|$CLOUDBEAVER_IMAGE|g" -e "s|\${CONNECT_IMAGE}|$CONNECT_IMAGE|g" \
    -e "s|\${POSTGRES_IMAGE}|$(image_of postgres.image)|g" -e "s|\${ORACLE_IMAGE}|$(image_of oracle.image)|g" \
    -e "s|\${BACKOFFICE_SQL_HASH}|$(sha256sum "$REPO/demo/cdc/backoffice.sql" | cut -c1-12)|g" \
    -e "s|\${ORACLE_SQL_HASH}|$(sha256sum "$REPO/demo/cdc/oracle.sql" | cut -c1-12)|g" \
    -e "s|\${COCKROACH_IMAGE}|$(image_of cockroachdb.image)|g" \
    -e "s|\${COCKROACH_SQL_HASH}|$(sha256sum "$REPO/demo/cdc/cockroach.sql" | cut -c1-12)|g" \
    -e "s|\${KEYCLOAK_IMAGE}|$(image_of keycloak.image)|g" \
    -e "s|\${KEYCLOAK_REALM_HASH}|$(sha256sum "$STACK/keycloak-realm.json" | cut -c1-12)|g" \
    -e "s|\${KEYCLOAK_KEYS_HASH}|$(cat "$RUN"/keycloak/* 2>/dev/null | sha256sum | cut -c1-12)|g" \
    -e "s|\${MANIFEST_HASH}|$(sha256sum "$STACK/$1" | cut -c1-12)|g" -e "s|\${TOKENS_HASH}|$(tokens_hash)|g" \
    "$STACK/$1"
}

# A manifest from demo/k8s, filled in and applied.
apply() { fill "$1" | k apply -f - >/dev/null; }

# curl to the front door (ports.yaml), trusting the certificate's CA. It tries again when the request was never
# sent: the connection failed (curl's 7) or its TLS handshake did (35), as when the node is starved while
# Oracle starts in the CDC leg.
tls_curl() {
  local out rc
  for _ in 1 2 3; do
    rc=0
    out=$(curl "${CURL_TLS[@]}" "$@") || rc=$?
    if [[ $rc != 7 && $rc != 35 ]]; then
      printf '%s' "$out"
      return $rc
    fi
    sleep 2
  done
  return $rc
}

# An API call as a demo user; fails the script on anything but the expected status.
api() {
  local user=$1 method=$2 path=$3 body=${4:-} expect=${5:-2}
  local out status
  out=$(mktemp)
  local args=(-s -o "$out" -w '%{http_code}' -X "$method" "$URL$path"
    -H "Authorization: Bearer $(token_of "$user")")
  if [[ -n "$body" ]]; then
    args+=(-H 'Content-Type: application/json' --data "$body")
  fi
  status=$(tls_curl "${args[@]}")
  if [[ "$status" != $expect* ]]; then
    echo "$user $method $path answered $status: $(cat "$out")" >&2
    rm -f "$out"
    return 1
  fi
  rm -f "$out"
}

# Runs SQL from stdin in SingleStore as root; prints rows tab-separated, without column names.
s2() {
  MSYS_NO_PATHCONV=1 k exec -i deployment/singlestore -- singlestore -uroot -p"$S2_ROOT_PASSWORD" -N -B \
    2> >(grep -v -e 'getopt: command not found' -e 'Using a password on the command line' >&2)
}

# A SQL file from demo/singlestore, with the singlestore user's catalog token and the root password put in.
s2_file() {
  sed -e "s|\${SINGLESTORE_TOKEN}|$(token_of singlestore)|g" -e "s|\${SINGLESTORE_ROOT_PASSWORD}|$S2_ROOT_PASSWORD|g" \
    "$REPO/demo/singlestore/$1" | s2
}

image_id() { docker image inspect -f '{{.Id}}' "$1" | sed 's/^sha256://' | cut -c1-12; }

# Built from this checkout on every up, so the stack never runs code older than the checkout; Gradle and
# Docker do nothing when nothing changed. SingleStore's image is tagged with the hash of the connector jar and
# its Dockerfile, so a change to either replaces the pod (and up seeds the new one). The cluster never pulls: images it runs
# come from the local Docker engine.
build() {
  say "building the catalog server and change-feed connector jars (no tests run)"
  (cd "$REPO" && ./gradlew -q :catalog-server:bootJar :singlestore-change-feed:connectorJar)
  local jar="$REPO/singlestore-change-feed/build/libs/nuna-changefeed-connector.jar"
  rm -rf "$RUN/images"
  mkdir -p "$RUN/images/singlestore" "$RUN/images/lake/lib"
  cp "$jar" "$RUN/images/singlestore/"
  unzip -q -j "$jar" 'lib/*' -d "$RUN/images/lake/lib"
  cp "$REPO/demo/LakeWriter.java" "$RUN/images/lake/"

  say "building the images: the catalog, SingleStore with the connector, the lake writer, CloudBeaver"
  docker build -q -f "$REPO/deploy/image/Dockerfile" -t nuna-catalog:demo "$REPO/catalog-server/build/libs" >/dev/null
  # Studio's SAML address, in the image, names the host.
  SINGLESTORE_IMAGE="nuna-demo-singlestore:$( (cat "$jar" "$STACK/singlestore.Dockerfile"; echo "$DEMO_HOST") \
    | sha256sum | cut -c1-12)"
  docker build -q -f "$STACK/singlestore.Dockerfile" --build-arg "DEMO_HOST=$DEMO_HOST" -t "$SINGLESTORE_IMAGE" \
    "$RUN/images/singlestore" >/dev/null
  docker build -q -f "$STACK/lake.Dockerfile" -t nuna-demo-lake:local "$RUN/images/lake" >/dev/null
  mkdir -p "$RUN/images/cloudbeaver"
  cp "$REPO/demo/CloudBeaverUsers.java" "$STACK/cloudbeaver-signin.conf" "$RUN/images/cloudbeaver/"
  CLOUDBEAVER_IMAGE="nuna-demo-cloudbeaver:$(cat "$STACK/cloudbeaver.Dockerfile" "$REPO/demo/CloudBeaverUsers.java" \
    "$STACK/cloudbeaver-signin.conf" | sha256sum | cut -c1-12)"
  docker build -q -f "$STACK/cloudbeaver.Dockerfile" -t "$CLOUDBEAVER_IMAGE" "$RUN/images/cloudbeaver" >/dev/null

  local image
  for image in "$(image_of postgres.image)" "$(image_of kafka.image)" "$(image_of silo.image)" busybox:1.37.0 \
      "$KAFKA_UI_IMAGE" "$(image_of keycloak.image)" "$TRINO_IMAGE" "$OAUTH2_PROXY_IMAGE" "$NGINX_IMAGE"; do
    docker image inspect "$image" >/dev/null 2>&1 || docker pull -q "$image" >/dev/null
  done
}

mint() {
  "$JAVA" "$REPO/demo/Mint.java" "$RUN"
}

# The issuer's public keys (served by Service idp), the lake writer's token, Trino's own, the people's for
# their CloudBeaver connections, and cdc's for Kafka Connect's Iceberg sinks.
publish_tokens() {
  k create configmap idp-keys --from-file=keys.json="$RUN/idp/keys.json" --dry-run=client -o yaml | k apply -f - >/dev/null
  k create secret generic demo-lake-token --from-literal=token="$(token_of lake)" --dry-run=client -o yaml | k apply -f - >/dev/null
  k create secret generic demo-trino-token --from-literal=token="$(token_of trino)" --dry-run=client -o yaml | k apply -f - >/dev/null
  k create secret generic demo-people-tokens --from-literal=ana="$(token_of ana)" --from-literal=gary="$(token_of gary)" \
    --from-literal=stella="$(token_of stella)" --from-literal=oscar="$(token_of oscar)" --dry-run=client -o yaml \
    | k apply -f - >/dev/null
  k create secret generic demo-cdc-token --from-literal=token="$(token_of cdc)" --dry-run=client -o yaml | k apply -f - >/dev/null
}

# Keycloak's realm signing key and certificate, and CloudBeaver's client and cookie secrets, made once per
# machine in $RUN/keycloak and kept, and published as Secret keycloak-keys (keycloak.yaml, cloudbeaver.yaml,
# singlestore.yaml). The key is fixed rather than Keycloak's own, made at each start, because SingleStore checks
# Studio's SAML sign-ins against its certificate, which it reads only as it starts.
publish_keycloak_keys() {
  local dir="$RUN/keycloak"
  if [[ ! -f "$dir/realm-certificate.pem" ]]; then
    mkdir -p "$dir"
    # In the folder, with bare file names: Git Bash must not turn the subject into a path, and its openssl, a
    # Windows program, cannot open /c/... paths that Git Bash has not turned into Windows ones.
    (cd "$dir" && MSYS_NO_PATHCONV=1 openssl req -quiet -x509 -newkey rsa:2048 -nodes -days 3650 \
      -subj "/CN=nuna-demo-keycloak" -keyout realm-private-key.pem -out realm-certificate.pem.new)
    openssl rand -hex 24 > "$dir/cloudbeaver-client-secret"
    openssl rand -hex 16 > "$dir/cloudbeaver-cookie-secret"
    mv "$dir/realm-certificate.pem.new" "$dir/realm-certificate.pem"
  fi
  # The realm file takes the key and certificate without their PEM lines.
  pem_body() { sed '/^-----/d' "$1" | tr -d '\r\n'; }
  k create secret generic keycloak-keys \
    --from-literal=realm-private-key="$(pem_body "$dir/realm-private-key.pem")" \
    --from-literal=realm-certificate="$(pem_body "$dir/realm-certificate.pem")" \
    --from-file=realm-certificate-pem="$dir/realm-certificate.pem" \
    --from-literal=cloudbeaver-client-secret="$(tr -d '\r\n' < "$dir/cloudbeaver-client-secret")" \
    --from-literal=cloudbeaver-cookie-secret="$(tr -d '\r\n' < "$dir/cloudbeaver-cookie-secret")" \
    --dry-run=client -o yaml | k apply -f - >/dev/null
}

# Whether $TLS holds the demo's own CA: its key beside it, and ca.crt still the demo's, not work's.
demo_ca() {
  [[ -f "$TLS/ca.key" && -f "$TLS/ca.crt" ]] \
    && [[ "$(cd "$TLS" && openssl pkey -pubout -in ca.key 2>/dev/null)" == "$(cd "$TLS" && openssl x509 -pubkey -noout -in ca.crt 2>/dev/null)" ]]
}

# The certificate the front door (ports.yaml) serves on every port, from $TLS, published as Secret demo-tls:
#   tls.crt  the server certificate for $DEMO_HOST, then any intermediate certificates (PEM)
#   tls.key  its private key (PEM, not encrypted)
#   ca.crt   the certificates of the CA that issued it (PEM), which demo.sh's own calls trust
# At work these are work's files (docs/install-at-work.md). At home up makes them: a demo CA, which can only
# vouch for localhost and 127.0.0.0/8 (its name constraints), made once, and a certificate from it for
# localhost, made again when it nears its end. A browser trusts them once the CA is in the user's trust store
# (print_access).
publish_tls() {
  if [[ ! -f "$TLS/tls.crt" && ! -f "$TLS/ca.crt" ]]; then
    if [[ "$DEMO_HOST" != localhost ]]; then
      echo "no certificate for $DEMO_HOST: put tls.crt, tls.key and ca.crt in $TLS (docs/install-at-work.md)" >&2
      return 1
    fi
    mkdir -p "$TLS"
    say "making the demo's CA and a certificate from it for localhost, in $TLS"
    # In the folder, with bare file names, as in publish_keycloak_keys.
    (cd "$TLS" && MSYS_NO_PATHCONV=1 openssl req -quiet -x509 -newkey rsa:3072 -nodes -days 3650 \
      -subj "/CN=nuna demo CA" -addext "basicConstraints=critical,CA:TRUE" \
      -addext "keyUsage=critical,keyCertSign,cRLSign" \
      -addext "nameConstraints=critical,permitted;DNS:localhost,permitted;IP:127.0.0.0/255.0.0.0" \
      -keyout ca.key -out ca.crt 2>/dev/null)
  fi
  # The demo's own certificate is made again a month before its end.
  if demo_ca && ! openssl x509 -checkend 2592000 -noout -in "$TLS/tls.crt" >/dev/null 2>&1; then
    (cd "$TLS" && MSYS_NO_PATHCONV=1 openssl req -quiet -x509 -CA ca.crt -CAkey ca.key -newkey rsa:2048 -nodes \
      -days 397 -subj "/CN=localhost" -addext "subjectAltName=DNS:localhost,IP:127.0.0.1" \
      -addext "extendedKeyUsage=serverAuth" -addext "basicConstraints=critical,CA:FALSE" \
      -keyout tls.key -out tls.crt 2>/dev/null)
  fi
  local file
  for file in tls.crt tls.key ca.crt; do
    if [[ ! -f "$TLS/$file" ]]; then
      echo "$TLS has no $file: the front door needs tls.crt, tls.key and ca.crt (docs/install-at-work.md)" >&2
      return 1
    fi
  done
  if ! (cd "$TLS" && openssl x509 -checkhost "$DEMO_HOST" -noout -in tls.crt >/dev/null); then
    echo "$TLS/tls.crt is not for $DEMO_HOST (NUNA_DEMO_HOST); it names: $(cd "$TLS" \
      && openssl x509 -noout -ext subjectAltName -in tls.crt | tail -n +2 | tr -d ' ')" >&2
    return 1
  fi
  if ! (cd "$TLS" && openssl x509 -checkend 0 -noout -in tls.crt >/dev/null); then
    echo "$TLS/tls.crt has expired" >&2
    return 1
  fi
  if [[ "$(cd "$TLS" && openssl x509 -noout -pubkey -in tls.crt)" != "$(cd "$TLS" && openssl pkey -pubout -in tls.key 2>/dev/null)" ]]; then
    echo "$TLS/tls.key is not tls.crt's key, or is encrypted (docs/install-at-work.md)" >&2
    return 1
  fi
  if ! (cd "$TLS" && openssl verify -CAfile ca.crt -untrusted tls.crt tls.crt >/dev/null 2>&1); then
    echo "$TLS/ca.crt did not issue tls.crt: it needs the CA's certificates, from tls.crt's issuer to the root" >&2
    return 1
  fi
  k create secret tls demo-tls --cert="$TLS/tls.crt" --key="$TLS/tls.key" --dry-run=client -o yaml \
    | k apply -f - >/dev/null
}

# Expired, about to expire, or minted before Mint.java had every user it has now.
tokens_expired() {
  [[ ! -f "$RUN/tokens/expires.txt" || ! -f "$RUN/tokens/trino.jwt" ]] && return 0
  local expires now
  expires=$(date -d "$(tr -d '\r\n' < "$RUN/tokens/expires.txt")" +%s)
  now=$(date +%s)
  (( expires - now < 3600 ))
}

# Before the stack publishes its ports for the first time, nothing else may hold them: a port-forward on 8181
# would answer in the catalog's place.
check_ports() {
  k get service catalog-public >/dev/null 2>&1 && return 0
  local port busy=()
  for port in 8180 8181 8182 8183 8184 8185 8186; do
    if (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then busy+=("$port"); fi
  done
  if (( ${#busy[@]} )); then
    echo "localhost port(s) ${busy[*]} are in use; the demo publishes 8180 to 8186. Stop what holds them." >&2
    return 1
  fi
}

wait_for_console() {
  for _ in $(seq 1 120); do
    curl -fs "${CURL_TLS[@]}" "$URL/ui/config.json" >/dev/null 2>&1 && return 0
    sleep 2
  done
  echo "the catalog did not answer on $URL; see: kubectl --context $CONTEXT -n $NS logs deployment/$RELEASE" >&2
  return 1
}

# By SQL, so the server's separation-of-duties check (D18) never sees these: each group keeps to one duty plus
# reading. ana's demo-analysts change data and definitions; stella's demo-stewards tag and read. gary's
# demo-grant-admins and oscar's demo-operators may hold no grant at all, so they hold none.
grants() {
  say "granting the demo groups and the engine matrix their privileges (by SQL)"
  k exec -i deployment/postgres -- psql -q -v ON_ERROR_STOP=1 -U catalog -d catalog <<'SQL'
INSERT INTO grant_record (catalog_id, securable_kind, securable_id, group_name, privilege, granted_by, granted_at)
SELECT c.id, 'CATALOG', c.id, g.group_name, g.privilege, 'demo-setup', now()
FROM catalog c,
     (VALUES ('lakehouse', 'demo-loaders', 'create'), ('lakehouse', 'demo-loaders', 'modify'),
             ('lakehouse', 'demo-analysts', 'create'), ('lakehouse', 'demo-analysts', 'modify'),
             ('lakehouse', 'demo-stewards', 'manage-tags'), ('lakehouse', 'demo-stewards', 'select'),
             ('engines', 'engine-clients', 'create'), ('engines', 'engine-clients', 'modify'),
             ('engines', 'demo-analysts', 'create'), ('engines', 'demo-analysts', 'modify'),
             ('engines', 'demo-stewards', 'select')) AS g(catalog_name, group_name, privilege)
WHERE c.name_norm = g.catalog_name
ON CONFLICT DO NOTHING;
SQL
}

# One run of the lake writer (seed or rate) as a Job: prints its output, deletes it, fails if it failed.
lake() {
  local mode=$1 job succeeded= failed=
  job="lake-$mode-$(date +%s)"
  sed -e "s|\${JOB}|$job|" -e "s|\${MODE}|$mode|" "$STACK/lake-job.yaml" | k apply -f - >/dev/null
  for _ in $(seq 1 300); do
    succeeded=$(k get job "$job" -o jsonpath='{.status.succeeded}')
    failed=$(k get job "$job" -o jsonpath='{.status.failed}')
    [[ -n "$succeeded$failed" ]] && break
    sleep 2
  done
  k logs "job/$job" 2>/dev/null | grep -v '^Picked up JAVA_TOOL_OPTIONS' || true
  k delete job "$job" --wait=false >/dev/null
  if [[ "$succeeded" != 1 ]]; then
    echo "the lake writer ($mode) did not succeed" >&2
    return 1
  fi
}

# Starts a pipeline unless it is running already.
start_pipeline() {
  local db=$1 name=$2 state
  state=$(echo "SELECT STATE FROM information_schema.PIPELINES WHERE DATABASE_NAME = '$db' AND PIPELINE_NAME = '$name';" | s2)
  if [[ "$state" != Running* ]]; then
    echo "START PIPELINE $db.$name;" | s2 >/dev/null
  fi
}

# The two running pipelines, created or replaced with the current token, and started.
feeds() {
  s2_file feeds.sql >/dev/null
  start_pipeline lake fx_rates_feed
  start_pipeline ops orders_to_lake
}

# Waits until the change feed has made a commit of one of the given kinds; fails after two minutes.
wait_for_commit() {
  local kinds=$1
  for _ in $(seq 1 60); do
    [[ "$(echo "SELECT COUNT(*) FROM ops.lake_commits WHERE kind IN ($kinds);" | s2)" != 0* ]] && return 0
    sleep 2
  done
  echo "the change feed made no $kinds commit. In demo/demo.sh sql:" >&2
  echo "  SELECT ERROR_MESSAGE FROM information_schema.PIPELINES_ERRORS ORDER BY ERROR_UNIX_TIMESTAMP DESC LIMIT 3;" >&2
  return 1
}

# Each part once: the Iceberg tables and governance (ConfigMap demo-seeded marks them done) and SingleStore's
# databases (done when ops exists; a new SingleStore pod starts empty). A new SingleStore's change feed finds
# offsets in Iceberg that it cannot continue from, so its first commit is a resnapshot. PostgreSQL and Silo
# keep their data only as long as their pods (a Docker Desktop restart empties both) but the ConfigMap
# outlives them, so the lake counts as seeded only while the catalog can also load the trades table; a new
# lake empties SingleStore too, since its databases are loaded from the lake.
seed() {
  local seeded= ns='markets%1Fequities%1Feu%1Fxetra'
  if k get configmap demo-seeded >/dev/null 2>&1 \
      && api ana GET "/v1/$CATALOG/namespaces/$ns/tables/trades" '' 200 2>/dev/null; then
    seeded=yes
  fi
  if [[ -z "$seeded" ]]; then
    k delete configmap demo-seeded --ignore-not-found >/dev/null
    echo "DROP DATABASE IF EXISTS ops; DROP DATABASE IF EXISTS lake;" | s2 >/dev/null
    say "writing the sample Iceberg tables through the catalog (demo/LakeWriter.java)"
    lake seed
  fi

  if [[ -n "$(echo "SHOW DATABASES LIKE 'ops';" | s2)" ]]; then
    echo "SingleStore already holds the sample data"
  else
    say "SingleStore: the ops and lake databases, and one-time loads from Iceberg"
    s2_file seed.sql >/dev/null
    s2_file load.sql >/dev/null

    say "SingleStore: the change feed of ops.orders into Iceberg, and the FX rates feed from Iceberg"
    feeds
    wait_for_commit "'snapshot', 'resnapshot'"
    s2 >/dev/null <<'SQL'
UPDATE ops.orders SET status = 'FILLED', updated_at = NOW(6) WHERE order_id = 1001;
UPDATE ops.orders SET qty = 60, status = 'PARTIAL', updated_at = NOW(6) WHERE order_id = 1002;
DELETE FROM ops.orders WHERE order_id = 1003;
INSERT INTO ops.orders VALUES (1005, 'SAP', 'SELL', 100, 182.500000, 'NEW', NOW(6));
SQL
    wait_for_commit "'changes'"
  fi

  [[ -n "$seeded" ]] && return 0
  say "governance: classification, owner, regulated area, a legal hold, a DR freeze"
  local m=/management/v1/catalogs/$CATALOG
  api stella PUT "$m/namespaces/finance/attributes/regulated" '{"value":"true","provenance":"demo-seed: compliance register"}'
  api stella PUT "$m/namespaces/finance%1Fledger/tables/journal/attributes/classification" '{"value":"confidential","provenance":"demo-seed: data catalogue"}'
  api stella PUT "$m/namespaces/markets/attributes/owner" '{"value":"markets-data-office","provenance":"demo-seed: HR feed"}'
  api stella PUT "$m/namespaces/$ns/tables/trades/attributes/classification" '{"value":"internal","provenance":"demo-seed: data catalogue"}'
  api stella POST "$m/namespaces/$ns/tables/trades/holds" '{"reason":"Matter 2026-114: trade surveillance review"}'
  api oscar PUT "$m/namespaces/reference/dr-freeze" '{"reason":"DR drill 2026-11-10: reference data frozen"}'

  say "grants through the management API, as the grant admin"
  api gary POST "$m/grants" '{"securable":{"type":"namespace","namespace":["markets"]},"group":"demo-auditors","privilege":"describe"}'
  api gary POST "$m/grants" "{\"securable\":{\"type\":\"table\",\"namespace\":[\"markets\",\"equities\",\"eu\",\"xetra\"],\"name\":\"trades\"},\"group\":\"demo-quants\",\"privilege\":\"select\"}"
  k create configmap demo-seeded >/dev/null
}

print_access() {
  local expires
  expires=$(tr -d '\r\n' < "$RUN/tokens/expires.txt")
  local web="https://$DEMO_HOST"
  cat <<EOF

Every page is HTTPS, with the certificate in $TLS.
EOF
  if demo_ca; then
    cat <<EOF
It is the demo's own: a browser trusts it once you trust the demo's CA, which vouches only for localhost,
in this user's Windows trust store (Windows asks you to confirm): certutil -user -addstore Root "$TLS/ca.crt"
EOF
  fi
  cat <<EOF

The demo's people sign in once, through Keycloak ($DEMO_HOST:8180), and the console, kafbat, CloudBeaver and
Studio ask no password after that: ana / ana-demo, gary / gary-demo, stella / stella-demo, oscar / oscar-demo.
Studio asks only the username, then "Sign in with SAML". To change user, use CloudBeaver's Logout (it ends
Keycloak's session too), or sign out of each UI and of Keycloak
($web:8180/realms/nuna/protocol/openid-connect/logout, then Logout), or use a private window.
To show everything in one sign-in, use presenter / presenter-demo in the console and kafbat: all four people's
groups at once. CloudBeaver and Studio have no presenter.

The console:  $URL/ui/   (catalogs lakehouse and engines)
Sign in, or paste one of these tokens (valid until $expires).

Data user  ana   (group demo-analysts: select on both catalogs)
$(token_of ana)

Grant admin  gary  (group demo-grant-admins: manage grants and read the audit trail; no data access)
$(token_of gary)

Tag steward stella, platform operator oscar, SingleStore, the lake writer, the engine matrix, Kafka Connect
and Trino have tokens too: $RUN/tokens/{stella,oscar,singlestore,lake,engine-matrix,cdc,trino}.jwt
oscar administers kafbat, Studio and CloudBeaver; in kafbat, ana, gary and stella administer topics, but only
read the catalog's (nuna.*), the Connect worker's and those starting with _. oscar reads kafbat's audit log.
Kafka:        kafbat on $web:8182
SingleStore:  Studio on $web:8183, or demo/demo.sh sql   (as root, password $S2_ROOT_PASSWORD;
              Studio takes no passwords, so not root)
Trino:        CloudBeaver on $web:8185: each person's connections sign in as them, to nuna through
              Trino with their own token, and to the CDC leg's databases
Keycloak:     $web:8180/admin   (user admin, password nuna-test; realm nuna)
Silo:         its console on $web:8184   (user nunatest, password nunatest-secret-key: the S3
              store's root, which sees every bucket)
Engines:      demo/demo.sh engines   (Spark, Kafka Connect, PyIceberg and SingleStore into catalog engines)
CDC:          demo/demo.sh cdc   (PostgreSQL, Oracle and CockroachDB into Iceberg history tables; stops Trino
              while it runs)
Kubernetes:   namespace $NS, context $CONTEXT
EOF
}

# The CDC leg (cdc.yaml), on demand: PostgreSQL backoffice, Oracle and CockroachDB, captured by Debezium into
# Kafka, and written by the Apache Iceberg sink into history tables, one row per change. Its pods do not fit
# beside Trino on an 8 GB Docker Desktop, so Trino stops while it runs; CloudBeaver stays, to query the
# databases. Kafka Connect's REST API is on $CONNECT_URL meanwhile.
cdc_running() { k get deployment connect >/dev/null 2>&1; }

# Built on every cdc (and on every up while the leg runs), tagged with the hash of its Dockerfile and plugin
# poms, so a change to either replaces the pod.
build_connect() {
  CONNECT_IMAGE="nuna-demo-connect:$(cat "$STACK/connect.Dockerfile" "$REPO"/demo/cdc/plugins/*/pom.xml | sha256sum | cut -c1-12)"
  docker build -q -f "$STACK/connect.Dockerfile" -t "$CONNECT_IMAGE" "$REPO/demo/cdc/plugins" >/dev/null
}

# A call to Kafka Connect's REST API, with the body of a PUT on stdin; prints the answer, fails the script on
# anything but 2xx.
connect_api() {
  local method=$1 path=$2 out in status
  out=$(mktemp)
  in=$(mktemp)
  local args=(-s -o "$out" -w '%{http_code}' -X "$method" "$CONNECT_URL$path")
  if [[ "$method" == PUT ]]; then
    # From a file, so a second try sends it again.
    cat > "$in"
    args+=(-H 'Content-Type: application/json' --data-binary "@$in")
  fi
  status=$(tls_curl "${args[@]}")
  rm -f "$in"
  if [[ "$status" != 2* ]]; then
    echo "Kafka Connect $method $path answered $status: $(cat "$out")" >&2
    rm -f "$out"
    return 1
  fi
  cat "$out"
  rm -f "$out"
}

# The history tables, as namespace path and table name for the catalog's API.
HISTORY_TABLES=("history%1Fpostgres%1Fsettlement instructions" "history%1Foracle%1Ftreasury payments"
  "history%1Fcockroach%1Fpositions holdings")

# Rows in a table of catalog lakehouse (its newest snapshot's total-records; 0 before the first commit).
table_rows() {
  curl -s "${CURL_TLS[@]}" -H "Authorization: Bearer $(token_of ana)" "$URL/v1/$CATALOG/namespaces/$1/tables/$2" \
    | grep -o '"total-records" *: *"[0-9]*"' | tail -1 | grep -o '[0-9]*' || echo 0
}

# Waits until a history table holds exactly the given number of rows; fails on more, or after three minutes.
wait_for_rows() {
  local ns=$1 table=$2 want=$3 have=0
  for _ in $(seq 1 90); do
    have=$(table_rows "$ns" "$table")
    (( have == want )) && return 0
    (( have > want )) && break
    sleep 2
  done
  echo "${ns//%1F/.}.$table holds $have rows, not $want. The connectors: curl -s --cacert '$TLS/ca.crt'" \
    "--ssl-revoke-best-effort '$CONNECT_URL/connectors?expand=status'" >&2
  return 1
}

# Runs SQL from stdin in the leg's PostgreSQL, Oracle and CockroachDB.
backoffice_sql() { k exec -i deployment/backoffice -- psql -q -v ON_ERROR_STOP=1 -U backoffice -d backoffice; }
oracle_sql() {
  { echo 'WHENEVER SQLERROR EXIT SQL.SQLCODE'; cat; echo 'EXIT'; } \
    | MSYS_NO_PATHCONV=1 k exec -i deployment/oracle -- sqlplus -s -l "$ORACLE_TREASURY" >/dev/null
}
# As root, with the client certificate the image's entrypoint made; it names the node 127.0.0.1, not localhost.
cockroach_sql() {
  MSYS_NO_PATHCONV=1 k exec -i deployment/cockroach -- ./cockroach sql --certs-dir=certs --host=127.0.0.1 \
    -d custody >/dev/null
}

# CockroachDB's connector sends DECIMAL as a string, whatever its settings, and the Iceberg sink would make that a
# string column. The sink does parse a string into a decimal column that exists, so the history table is made here,
# with the columns the sink makes for PostgreSQL's (precision 38, NOT NULL as required); the sink adds _cdc.
# updated_at arrives as microseconds since the epoch, which cockroach-history.json converts to a timestamp.
cockroach_history_table() {
  local path="" names="" level
  for level in history cockroach positions; do
    path+="${path:+%1F}$level"
    names+="${names:+, }\"$level\""
    api cdc GET "/v1/$CATALOG/namespaces/$path" '' 200 2>/dev/null \
      || api cdc POST "/v1/$CATALOG/namespaces" "{\"namespace\": [$names]}" 200
  done
  api cdc POST "/v1/$CATALOG/namespaces/$path/tables" '{"name": "holdings", "schema": {"type": "struct", "fields": [
    {"id": 1, "name": "holding_id", "required": true, "type": "long"},
    {"id": 2, "name": "account", "required": true, "type": "string"},
    {"id": 3, "name": "isin", "required": true, "type": "string"},
    {"id": 4, "name": "quantity", "required": true, "type": "decimal(38, 4)"},
    {"id": 5, "name": "market_value", "required": true, "type": "decimal(38, 2)"},
    {"id": 6, "name": "currency", "required": true, "type": "string"},
    {"id": 7, "name": "status", "required": true, "type": "string"},
    {"id": 8, "name": "updated_at", "required": true, "type": "timestamptz"}]}}' 200
}

# The leg's databases, worker and connectors, as cdc.yaml and demo/cdc have them; the connectors' configs are
# put in place (the same config twice changes nothing).
cdc_apply() {
  k create configmap backoffice-initdb --from-file=backoffice.sql="$REPO/demo/cdc/backoffice.sql" \
    --dry-run=client -o yaml | k apply -f - >/dev/null
  k create configmap oracle-startdb --from-file=cdc.sql="$REPO/demo/cdc/oracle.sql" --dry-run=client -o yaml \
    | k apply -f - >/dev/null
  k create configmap cockroach-initdb --from-file=cockroach.sql="$REPO/demo/cdc/cockroach.sql" \
    --dry-run=client -o yaml | k apply -f - >/dev/null
  apply cdc.yaml
  apply ports.yaml
  k rollout status deployment/backoffice --timeout=3m >/dev/null
  k rollout status deployment/cockroach --timeout=3m >/dev/null
  k rollout status deployment/connect --timeout=5m >/dev/null
  # Oracle restarts once into archive log mode (demo/cdc/oracle.sql): about two minutes.
  k rollout status deployment/oracle --timeout=10m >/dev/null
  for _ in $(seq 1 30); do
    curl -fs "${CURL_TLS[@]}" "$CONNECT_URL/connectors" >/dev/null 2>&1 && break
    sleep 2
  done
  local connector
  for connector in backoffice-source oracle-source cockroach-source backoffice-history oracle-history \
    cockroach-history; do
    connect_api PUT "/connectors/$connector/config" < "$REPO/demo/cdc/connectors/$connector.json" >/dev/null
  done
}

cdc_start() {
  if tokens_expired; then
    mint
  fi
  publish_tokens
  annotate_tokens
  say "building the Kafka Connect image (Debezium 3.7.1, the Apache Iceberg 1.12.0 sink)"
  build_connect
  if cdc_running; then
    say "the CDC leg is running: bringing it up to date"
    cdc_apply
    cdc_access
    return
  fi
  local image
  image=$(image_of oracle.image)
  if ! docker image inspect "$image" >/dev/null 2>&1; then
    say "pulling $image (about 5 GB, once)"
    docker pull -q "$image" >/dev/null
  fi
  image=$(image_of cockroachdb.image)
  docker image inspect "$image" >/dev/null 2>&1 || docker pull -q "$image" >/dev/null
  say "stopping Trino: the CDC leg needs its memory"
  k scale deployment/trino --replicas=0 >/dev/null
  k wait --for=delete pod -l app=trino --timeout=2m >/dev/null
  # History tables left by an earlier run recorded databases that are gone; each run starts its own.
  local entry ns table
  for entry in "${HISTORY_TABLES[@]}"; do
    read -r ns table <<<"$entry"
    if api ana GET "/v1/$CATALOG/namespaces/$ns/tables/$table" '' 200 2>/dev/null; then
      api cdc DELETE "/v1/$CATALOG/namespaces/$ns/tables/$table?purgeRequested=true" '' 204
    fi
  done
  cockroach_history_table

  say "starting the CDC leg: PostgreSQL backoffice, Oracle Free, CockroachDB and Kafka Connect (a few minutes)"
  cdc_apply
  say "waiting for Debezium's snapshots of the three tables to reach Iceberg"
  wait_for_rows "history%1Fpostgres%1Fsettlement" instructions 4
  wait_for_rows "history%1Foracle%1Ftreasury" payments 4
  wait_for_rows "history%1Fcockroach%1Fpositions" holdings 4

  say "changing rows in each database: each change is a row in its history table"
  backoffice_sql >/dev/null <<'SQL'
UPDATE settlement.instructions SET status = 'MATCHED', updated_at = now() WHERE instruction_id = 5001;
UPDATE settlement.instructions SET status = 'SETTLED', updated_at = now() WHERE instruction_id = 5003;
DELETE FROM settlement.instructions WHERE instruction_id = 5004;
INSERT INTO settlement.instructions (instruction_id, trade_ref, isin, quantity, amount, currency, status)
VALUES (5005, 'XETR-2026-11-10-000201', 'DE0007164600', 60, 10950.00, 'EUR', 'PENDING');
SQL
  oracle_sql <<'SQL'
UPDATE treasury.payments SET status = 'RELEASED', updated_at = SYSTIMESTAMP WHERE payment_id = 7001;
DELETE FROM treasury.payments WHERE payment_id = 7002;
INSERT INTO treasury.payments (payment_id, account, amount, currency, status)
VALUES (7005, 'DE89370400440532013000', 10950.00, 'EUR', 'SCHEDULED');
COMMIT;
SQL
  cockroach_sql <<'SQL'
UPDATE positions.holdings SET status = 'PLEDGED', updated_at = now() WHERE holding_id = 9001;
DELETE FROM positions.holdings WHERE holding_id = 9002;
INSERT INTO positions.holdings (holding_id, account, isin, quantity, market_value, currency, status)
VALUES (9005, 'CUST-0002', 'DE0007164600', 60, 10950.00, 'EUR', 'PENDING');
SQL
  wait_for_rows "history%1Fpostgres%1Fsettlement" instructions 8
  wait_for_rows "history%1Foracle%1Ftreasury" payments 7
  wait_for_rows "history%1Fcockroach%1Fpositions" holdings 7
  cdc_access
}

cdc_access() {
  cat <<EOF

The CDC leg, into catalog lakehouse as user cdc (group demo-loaders):
  PostgreSQL backoffice, settlement.instructions  ->  history.postgres.settlement.instructions   (demo/demo.sh psql)
  Oracle FREEPDB1, TREASURY.PAYMENTS               ->  history.oracle.treasury.payments          (demo/demo.sh sqlplus)
  CockroachDB custody, positions.holdings          ->  history.cockroach.positions.holdings      (demo/demo.sh cockroach)
Kafka Connect: curl -s --cacert '$TLS/ca.crt' --ssl-revoke-best-effort '$CONNECT_URL/connectors?expand=status'.
kafbat (https://$DEMO_HOST:8182) shows Debezium's change events as they arrive, and the console shows each sink
commit as a snapshot of its history table.
Query the source databases in CloudBeaver (https://$DEMO_HOST:8185): each person's backoffice, treasury and
custody connections sign in as them. Trino is stopped meanwhile: demo/demo.sh cdc stop deletes the leg and
starts it again. Then read the history in CloudBeaver's Trino connection, oldest change first:
  SELECT "_cdc".op, "_cdc".ts, instruction_id, status FROM lakehouse."history.postgres.settlement".instructions
  ORDER BY "_cdc"."offset";
EOF
}

# Deletes the leg's pods, then its Kafka topics and consumer groups, so the next cdc starts from nothing; the
# history tables stay, for Trino to read. The topics go after the worker, which would otherwise make its own
# again, and from a pod of their own: a Kafka tool in the broker's pod could push it past its memory limit.
cdc_stop() {
  say "deleting the CDC leg: its databases, Kafka Connect, and its Kafka topics (the history tables stay)"
  k delete deployment,service,configmap -l nuna-demo/cdc=true --ignore-not-found >/dev/null
  k delete configmap backoffice-initdb oracle-startdb cockroach-initdb --ignore-not-found >/dev/null
  k wait --for=delete pod -l 'app in (backoffice, oracle, cockroach, connect)' --timeout=3m >/dev/null 2>&1 || true
  k delete pod cdc-cleanup --ignore-not-found >/dev/null
  MSYS_NO_PATHCONV=1 k run cdc-cleanup -i --rm --quiet --restart=Never --image="$(image_of kafka.image)" \
    --image-pull-policy=Never --env=KAFKA_HEAP_OPTS=-Xmx128m --command -- sh -c '
      bin=/opt/kafka/bin; kafka=--bootstrap-server=kafka:9092
      $bin/kafka-topics.sh $kafka --delete --if-exists --topic "cdc-connect-.*|backoffice.*|oracle.*|cockroach.*"
      for group in $($bin/kafka-consumer-groups.sh $kafka --list | grep -E "backoffice|oracle|cockroach|nuna-demo-cdc"); do
        $bin/kafka-consumer-groups.sh $kafka --delete --group "$group" >/dev/null
      done' >/dev/null
  say "starting Trino again"
  k scale deployment/trino --replicas=1 >/dev/null
}

up() {
  mkdir -p "$RUN"
  build
  if tokens_expired; then
    mint
  fi
  kubectl --context "$CONTEXT" create namespace "$NS" --dry-run=client -o yaml | kubectl --context "$CONTEXT" apply -f - >/dev/null
  check_ports
  publish_tokens
  publish_keycloak_keys
  publish_tls

  say "starting PostgreSQL, Kafka, kafbat, the issuer's key server, Keycloak and Silo (namespace $NS, context $CONTEXT)"
  apply stack.yaml
  k patch deployment kafka-ui --type strategic --patch "$(fill kafka-ui-signin.yaml)" >/dev/null
  apply store-silo.yaml
  fill keycloak-realm.json > "$RUN/keycloak-realm.json"
  k create configmap keycloak-realm --from-file=nuna.json="$RUN/keycloak-realm.json" --dry-run=client -o yaml \
    | k apply -f - >/dev/null
  apply keycloak.yaml
  k wait --for=condition=available --timeout=8m deployment/postgres deployment/kafka deployment/idp \
    deployment/kafka-ui deployment/keycloak deployment/s3 >/dev/null
  # The seed job makes the warehouse bucket. It runs on every up: a new Silo pod starts without it.
  k delete job seed --ignore-not-found >/dev/null
  apply seed.yaml
  k wait --for=condition=complete --timeout=3m job/seed >/dev/null

  say "installing the catalog (chart deploy/helm/nuna-catalog) and publishing it on $URL"
  # The image's id as a pod annotation: a rebuilt image under the same tag rolls the server, an unchanged
  # one does not.
  fill demo-values.yaml > "$RUN/demo-values.yaml"
  helm --kube-context "$CONTEXT" -n "$NS" upgrade --install "$RELEASE" "$REPO/deploy/helm/nuna-catalog" \
    -f "$STACK/catalog-values.yaml" -f "$RUN/demo-values.yaml" \
    --set-string "podAnnotations.nuna-demo/image=$(image_id nuna-catalog:demo)" --wait --timeout 10m >/dev/null
  # The server makes its tables only as it starts, so one that outlived its PostgreSQL pod is restarted.
  if [[ -z "$(k exec deployment/postgres -- psql -U catalog -d catalog -tAc "SELECT to_regclass('flyway_schema_history')")" ]]; then
    say "PostgreSQL is new and empty: restarting the catalog so it makes its tables"
    k rollout restart "deployment/$RELEASE" >/dev/null
    k rollout status "deployment/$RELEASE" --timeout=10m >/dev/null
  fi
  apply ports.yaml
  wait_for_console
  grants

  say "starting SingleStore ($SINGLESTORE_IMAGE)"
  apply singlestore.yaml
  k rollout status deployment/singlestore --timeout=15m >/dev/null
  s2_file users.sql >/dev/null
  seed

  if cdc_running; then
    # trino.yaml asks for one replica, which would start Trino beside the leg.
    say "the CDC leg is running: Trino stays stopped; bringing the leg and CloudBeaver ($CLOUDBEAVER_IMAGE) up to date"
    build_connect
    cdc_apply
  else
    say "starting Trino ($TRINO_IMAGE)"
    apply trino.yaml
    k rollout status deployment/trino --timeout=5m >/dev/null
  fi
  say "starting CloudBeaver ($CLOUDBEAVER_IMAGE), with each person's connections to Trino and the CDC leg's databases"
  apply cloudbeaver.yaml
  k rollout status deployment/cloudbeaver --timeout=5m >/dev/null
  print_access
}

# The memory the CDC leg holds is what Spark's Job and the lake writer's need.
refuse_while_cdc() {
  if cdc_running; then
    echo "the CDC leg is running, and $1 does not fit beside it: demo/demo.sh cdc stop first" >&2
    exit 1
  fi
}

# The engine matrix's tests against this stack: its engines run as Jobs here, into catalog engines, and the
# stack stays as it is. Results are in catalog-tests-engines/build/engines-run. Trino and CloudBeaver stop
# for the run: Spark's Job asks for 2 GiB, which an 8 GB Docker Desktop has only without them.
engines() {
  if tokens_expired; then
    mint
    publish_tokens
    annotate_tokens
  fi
  say "stopping Trino and CloudBeaver for the run"
  k scale deployment/trino deployment/cloudbeaver --replicas=0 >/dev/null
  k wait --for=delete pod -l 'app in (trino, cloudbeaver)' --timeout=2m >/dev/null
  say "running the engine matrix against namespace $NS"
  local status=0
  (cd "$REPO" && ./gradlew :catalog-tests-engines:test --rerun -Pnightly --tests EngineMatrixTest \
    -Pengines.cluster="$CONTEXT" -Pengines.stack="$NS" -Pengines.token-file="$RUN/tokens/engine-matrix.jwt" -Ps3=silo) \
    || status=$?
  say "starting Trino and CloudBeaver again"
  k scale deployment/trino deployment/cloudbeaver --replicas=1 >/dev/null
  return "$status"
}

case "${1:-}" in
  up)
    up
    ;;
  engines)
    refuse_while_cdc "the engine matrix"
    engines
    ;;
  token)
    if tokens_expired; then
      mint
      publish_tokens
      say "re-minted; replacing SingleStore's running pipelines and restarting Trino, CloudBeaver and Kafka Connect so they use the new tokens"
      feeds
      annotate_tokens
      if cdc_running; then
        k rollout restart deployment/connect >/dev/null
      fi
    fi
    print_access
    ;;
  sql)
    MSYS_NO_PATHCONV=1 k exec -it deployment/singlestore -- singlestore -uroot -p"$S2_ROOT_PASSWORD" ops
    ;;
  rate)
    refuse_while_cdc "the lake writer"
    lake rate
    ;;
  cdc)
    case "${2:-}" in
      "") cdc_start ;;
      stop) cdc_stop ;;
      *) sed -n '2,23p' "$0"; exit 2 ;;
    esac
    ;;
  psql)
    # Inside the pod psql connects by socket, which the image trusts: no password asked.
    k exec -it deployment/backoffice -- psql -U "${2:-backoffice}" -d backoffice
    ;;
  sqlplus)
    # A person's Oracle password is their Keycloak one, <user>-demo (demo/cdc/oracle.sql).
    login=$ORACLE_TREASURY
    if [[ -n "${2:-}" ]]; then login="$2/\"$2-demo\"@//localhost:1521/FREEPDB1"; fi
    MSYS_NO_PATHCONV=1 k exec -it deployment/oracle -- sqlplus -l "$login"
    ;;
  cockroach)
    # Root signs in with the client certificate the image made; a person with their Keycloak password,
    # <user>-demo (demo/cdc/cockroach.sql).
    if [[ -n "${2:-}" ]]; then
      MSYS_NO_PATHCONV=1 k exec -it deployment/cockroach -- ./cockroach sql \
        --url "postgresql://$2:$2-demo@localhost:26257/custody?sslmode=disable"
    else
      MSYS_NO_PATHCONV=1 k exec -it deployment/cockroach -- ./cockroach sql --certs-dir=certs --host=127.0.0.1 -d custody
    fi
    ;;
  status)
    k get pods,services
    ;;
  down)
    helm --kube-context "$CONTEXT" -n "$NS" uninstall "$RELEASE" --wait 2>/dev/null || true
    kubectl --context "$CONTEXT" delete namespace "$NS" --ignore-not-found --wait=true
    ;;
  *)
    sed -n '2,23p' "$0"
    exit 2
    ;;
esac
