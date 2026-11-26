#!/usr/bin/env bash
# The local demo of nuna-catalog, on Docker Desktop's Kubernetes: the stack it shares with the engine matrix
# (demo/k8s), its sample data, and the matrix's engines on demand. See demo/README.md.
#
#   demo/demo.sh up       build the jars and images, start the core stack, load sample data once, with the
#                         catalog's branch dev and tag sample, turn gen, ss and kafka back on if they were, print
#                         tokens; run in the main checkout, it builds that checkout's HEAD as it was at the start,
#                         from a worktree of its own (backlog 274), and names the commit
#   demo/demo.sh gen      turn on the brokerage world: its tables and its live generator, which writes through
#                         Kafka (with Kafka Connect) or SingleStore or both, as the switches kafka and ss say
#                         (turns kafka on if neither is)
#   demo/demo.sh gen stop turn the generator off, and Kafka with it unless the CDC leg runs
#   demo/demo.sh ss       turn on SingleStore, with its sample databases and change feeds; the generator's orders
#                         go to it again
#   demo/demo.sh ss stop  turn SingleStore off; its databases stay on its volume, and the generator carries on
#                         through Kafka (turns kafka on if it is off)
#   demo/demo.sh kafka    let the generator use Kafka: every feed but the orders goes through Kafka and its sink
#   demo/demo.sh kafka stop  stop Kafka (refused while the CDC leg runs); the generator carries on through
#                         SingleStore, a change feed per table into brokerage.singlestore, or stops if ss is off
#   demo/demo.sh metrics  turn on the metrics stack: an OpenTelemetry collector, Prometheus, Tempo and Grafana
#                         (port 8188) with nuna's dashboards, which the catalog and the maintenance worker send their
#                         metrics and traces to (refused when Docker Desktop has too little memory left)
#   demo/demo.sh metrics stop  turn it off; its series, traces and Grafana's database stay on their volumes
#   demo/demo.sh engines  run the engine matrix (Spark, Kafka Connect, PyIceberg, SingleStore) against the stack
#   demo/demo.sh token    print the URLs and the tokens again (re-mints them if they have expired)
#   demo/demo.sh sql      open a SingleStore prompt (databases ops, lake and brokerage)
#   demo/demo.sh rate     append an FX rate to Iceberg (markets.fx.rates); SingleStore loads it into lake.fx_rates
#   demo/demo.sh brokerage logs  follow the brokerage world's generator
#   demo/demo.sh cdc      start the CDC leg: PostgreSQL, Oracle and CockroachDB, through Debezium and Kafka
#                         Connect, into Iceberg history tables (starts Kafka; stops Trino, the audit table writer
#                         and the generator while it runs)
#   demo/demo.sh cdc stop delete the CDC leg (the history tables stay), start Trino and the writer again, and the
#                         generator if gen is on; Kafka stops unless gen is on
#   demo/demo.sh psql [user]     a psql prompt on the CDC leg's PostgreSQL, database backoffice (default backoffice)
#   demo/demo.sh sqlplus [user]  a SQL*Plus prompt on the CDC leg's Oracle, PDB FREEPDB1 (default treasury)
#   demo/demo.sh cockroach [user]  a SQL prompt on the CDC leg's CockroachDB, database custody (default root)
#   demo/demo.sh status   show the deployments, pods and services, and Kafka Connect's connectors
#   demo/demo.sh down     stop and delete everything but the data: PostgreSQL's, Silo's, Kafka's, SingleStore's
#                         and the metrics stack's volumes stay, and the next up carries on from them (the CDC leg is deleted, as cdc stop does)
#   demo/demo.sh wipe     down, and delete the volumes too: the next up writes fresh sample data (keeps the signing
#                         key, tokens, Keycloak keys, the passwords and the certificates, as down does)
#
# Needs bash (Git Bash on Windows), Docker Desktop with Kubernetes on, kubectl, helm, curl, unzip, openssl (Git
# Bash has it), and JDK 25 (JAVA_HOME or java on PATH). kubectl and helm always use NUNA_DEMO_CONTEXT (default
# docker-desktop), never the current context, and the stack is namespace NUNA_DEMO_NAMESPACE (default
# nuna-demo). Docker Desktop's cluster runs images from the local Docker engine and serves LoadBalancer
# Services on localhost. The signing key, tokens, Keycloak's keys, the certificate and the catalog's database's CA
# and certificates (db_tls), and every password and key the demo uses (demo_secrets: none is in the repository) are
# kept in NUNA_DEMO_HOME (default ~/.nuna-demo); the people's passwords are listed in its file people.
#
# Every page is HTTPS, at https://<NUNA_DEMO_HOST>:<port> (default localhost), with the certificate in
# $NUNA_DEMO_HOME/tls (publish_tls). At work, put work's certificate there and set NUNA_DEMO_HOST to its name
# (docs/install-at-work.md).
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN="${NUNA_DEMO_HOME:-$HOME/.nuna-demo}"

# One commit a run (backlog 274). Started in the main checkout, a command that reads this repo over its run (up's
# Gradle and Docker builds, the k8s files, the engine matrix's tests, this script itself, which bash reads as it
# goes) runs instead from $RUN/checkout: a worktree detached at the main checkout's HEAD as it was at the start, and
# locked, so worktree cleanup leaves it alone, as the overnight run keeps its own (backlog 166). A landing during the
# run moves the main checkout's files, not these, so it can neither stop the run nor mix commits into its images.
# Ignored files (Gradle's build folders) stay between runs, so a build there is incremental. One command at a time
# holds it ($RUN/checkout.pid); another is refused while it runs. Run from any other worktree, demo.sh runs in
# place, at that worktree's files: its agent owns them.
if [[ -z "${NUNA_DEMO_COMMIT:-}" ]]; then
  case "${1:-}" in
    up | engines | gen | ss | kafka | cdc | metrics | token | rate)
      if [[ "$(git -C "$REPO" rev-parse --absolute-git-dir)" \
          == "$(git -C "$REPO" rev-parse --path-format=absolute --git-common-dir)" ]]; then
        mkdir -p "$RUN"
        if ! (set -o noclobber; echo $$ > "$RUN/checkout.pid") 2>/dev/null; then
          holder=$(cat "$RUN/checkout.pid" 2>/dev/null || true)
          if [[ -n "$holder" ]] && kill -0 "$holder" 2>/dev/null; then
            echo "another demo.sh (pid $holder) is running from $RUN/checkout: wait for it to end" >&2
            exit 1
          fi
          rm -f "$RUN/checkout.pid"
          (set -o noclobber; echo $$ > "$RUN/checkout.pid") 2>/dev/null \
            || { echo "another demo.sh has just started from $RUN/checkout: wait for it to end" >&2; exit 1; }
        fi
        commit=$(git -C "$REPO" rev-parse HEAD)
        if [[ ! -e "$RUN/checkout/.git" ]]; then
          # Forced twice: a locked worktree whose folder is gone stays registered.
          git -C "$REPO" worktree add --quiet --force --force --detach "$RUN/checkout" "$commit"
        fi
        git -C "$REPO" worktree lock --reason "demo.sh's checkout (backlog 274)" "$RUN/checkout" 2>/dev/null || true
        git -C "$RUN/checkout" checkout --quiet --force --detach "$commit"
        git -C "$RUN/checkout" clean -q -fd
        NUNA_DEMO_COMMIT=$commit exec bash "$RUN/checkout/demo/demo.sh" "$@"
      fi
      ;;
  esac
fi
if [[ -n "${NUNA_DEMO_COMMIT:-}" && "$(cat "$RUN/checkout.pid" 2>/dev/null)" == "$$" ]]; then
  trap 'rm -f "$RUN/checkout.pid"' EXIT
fi
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
# The demo's passwords and keys, made once per machine and kept by down and wipe (demo_secrets; backlog 282), and the
# people's passwords, which up writes to $PEOPLE_FILE.
SECRETS="$RUN/secrets"
PEOPLE_FILE="$RUN/people"
PEOPLE=(ana gary stella oscar presenter)
# The catalog's database's CA and certificates (db_tls), kept by down and wipe.
DB_TLS="$RUN/db-tls"
KAFKA_UI_IMAGE=kafbat/kafka-ui:v1.5.0
SINGLESTORE_IMAGE=
TRINO_IMAGE=trinodb/trino:484
# Signs people in to CloudBeaver through Keycloak (cloudbeaver.yaml).
OAUTH2_PROXY_IMAGE=quay.io/oauth2-proxy/oauth2-proxy:v7.15.6
CLOUDBEAVER_IMAGE=
CONNECT_IMAGE=
# The engine matrix's Spark image, which the maintenance worker's compaction Jobs run (maintenance-values.yaml).
SPARK_IMAGE=nuna-engines/spark:4.1.4-iceberg-1.12.0
# The brokerage world's generator (brokerage.Dockerfile), and its image's id.
BROKERAGE_IMAGE=
BROKERAGE_IMAGE_ID=
# Kafka Connect's REST API (connect.yaml, ports.yaml).
CONNECT_URL="https://$DEMO_HOST:8186"
# Its password, user operator, which the front door asks for (backlog 283), as a curl config file.
CONNECT_AUTH=(-K "$SECRETS/connect.curlrc")
# The front door (ports.yaml): TLS on every port the demo publishes.
NGINX_IMAGE=nginx:1.30.6-alpine
# The switch metrics (metrics.yaml), and what its four pods ask, 64 + 256 + 128 + 256 MiB. Turning it on also rolls the
# catalog, whose new pod (768 MiB) starts before the old one stops, so metrics_start checks Docker Desktop has both free.
OTEL_COLLECTOR_IMAGE=otel/opentelemetry-collector:0.166.0
PROMETHEUS_IMAGE=prom/prometheus:v3.16.0
TEMPO_IMAGE=grafana/tempo:2.10.10
GRAFANA_IMAGE=grafana/grafana:13.3.2
METRICS_MIB=704
CATALOG_MIB=768

k() { kubectl --context "$CONTEXT" -n "$NS" "$@"; }

say() { printf '\n== %s\n' "$*"; }

# The commit this run's files are at, with its subject, marked when the worktree it runs in has uncommitted changes.
commit_here() {
  git -C "$REPO" log -1 --format='%h %s' --abbrev=12
  [[ -z "$(git -C "$REPO" status --porcelain)" ]] || echo "  (with uncommitted changes in $REPO)"
}

token_of() { tr -d '\r\n' < "$RUN/tokens/$1.jwt"; }

# Changes whenever Mint.java mints. Trino, CloudBeaver, Kafka Connect and the brokerage world's generator read
# tokens only as they start and carry this as a pod annotation (trino.yaml, cloudbeaver.yaml, connect.yaml,
# brokerage.yaml), so new tokens replace their pods.
tokens_hash() { sha256sum "$RUN/tokens/expires.txt" | cut -c1-12; }

# Sets that annotation as up would, outside up: new tokens replace the pods, and the same tokens change nothing.
annotate_tokens() {
  local deployment
  for deployment in trino cloudbeaver connect brokerage; do
    k get deployment "$deployment" >/dev/null 2>&1 || continue
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
    -e "s|\${BROKERAGE_IMAGE}|$BROKERAGE_IMAGE|g" -e "s|\${BROKERAGE_IMAGE_ID}|$BROKERAGE_IMAGE_ID|g" \
    -e "s|\${BROKERAGE_ROUTE}|$(world_route)|g" \
    -e "s|\${POSTGRES_IMAGE}|$(image_of postgres.image)|g" -e "s|\${ORACLE_IMAGE}|$(image_of oracle.image)|g" \
    -e "s|\${BACKOFFICE_SQL_HASH}|$(sha256sum "$REPO/demo/cdc/backoffice.sql" | cut -c1-12)|g" \
    -e "s|\${ORACLE_SQL_HASH}|$(sha256sum "$REPO/demo/cdc/oracle.sql" | cut -c1-12)|g" \
    -e "s|\${COCKROACH_IMAGE}|$(image_of cockroachdb.image)|g" \
    -e "s|\${COCKROACH_SQL_HASH}|$(sha256sum "$REPO/demo/cdc/cockroach.sql" | cut -c1-12)|g" \
    -e "s|\${KEYCLOAK_IMAGE}|$(image_of keycloak.image)|g" \
    -e "s|\${KEYCLOAK_REALM_HASH}|$(sha256sum "$STACK/keycloak-realm.json" | cut -c1-12)|g" \
    -e "s|\${KEYCLOAK_KEYS_HASH}|$(cat "$RUN"/keycloak/* "$SECRETS"/* 2>/dev/null | sha256sum | cut -c1-12)|g" \
    -e "s|\${OTEL_COLLECTOR_IMAGE}|$OTEL_COLLECTOR_IMAGE|g" -e "s|\${PROMETHEUS_IMAGE}|$PROMETHEUS_IMAGE|g" \
    -e "s|\${TEMPO_IMAGE}|$TEMPO_IMAGE|g" -e "s|\${GRAFANA_IMAGE}|$GRAFANA_IMAGE|g" \
    -e "s|\${GRAFANA_FILES_HASH}|$(cat "$REPO"/deploy/grafana/dashboards/*.json "$REPO"/deploy/grafana/rules/*.yaml | sha256sum | cut -c1-12)|g" \
    -e "s|\${MANIFEST_HASH}|$(sha256sum "$STACK/$1" | cut -c1-12)|g" -e "s|\${TOKENS_HASH}|$(tokens_hash)|g" \
    -e "s|\${S3_SECRET_KEY}|$(secret_of silo-root 2>/dev/null)|g" -e "s|\${DB_TLS_HASH}|$(db_tls_hash)|g" \
    "$STACK/$1"
}

# A manifest from demo/k8s, filled in and applied; or deleted, with whatever of it is there but its volume claims,
# which keep the data for the next start (backlog 237; demo.sh wipe deletes them).
apply() { fill "$1" | k apply -f - >/dev/null; }
unapply() {
  fill "$1" | awk 'BEGIN { RS = "\n---\n"; ORS = "\n---\n" } !/\nkind: PersistentVolumeClaim\n/' \
    | k delete --ignore-not-found -f - >/dev/null
}

# The switches gen, ss and kafka (D50; kafka since 2026-11-24, backlog 238): on or off, kept across ups (and the
# overnight run's rebuild) in $SWITCHES, one name a line; all off on a new machine. kafka says whether the generator
# writes through Kafka; Kafka runs while it does or the CDC leg runs. The generator needs a way, Kafka or SingleStore
# (Paul, 2026-11-24), so while gen is on, kafka or ss is. The switch metrics (backlog 233) is kept the same way. The
# switch cdc is on while its leg's deployments exist, and down ends it.
SWITCHES="$RUN/switches"
is_on() { [[ -f "$SWITCHES" ]] && grep -qx "$1" "$SWITCHES"; }
# A switches file from before kafka was a switch: the generator wrote through Kafka whenever gen was on.
if [[ -f "$SWITCHES" ]] && ! grep -qx '# kafka is a switch' "$SWITCHES"; then
  if grep -qx gen "$SWITCHES"; then echo kafka >> "$SWITCHES"; fi
  echo '# kafka is a switch' >> "$SWITCHES"
fi
# The generator's route (BROKERAGE_ROUTE, demo-datagen's Route): both, kafka or singlestore.
world_route() {
  if is_on ss && is_on kafka; then echo both; elif is_on ss; then echo singlestore; else echo kafka; fi
}
set_switch() {
  local rest
  rest=$(grep -vx "$1" "$SWITCHES" 2>/dev/null || true)
  { [[ -z "$rest" ]] || printf '%s\n' "$rest"; [[ "$2" == off ]] || echo "$1"; } > "$SWITCHES.new"
  mv "$SWITCHES.new" "$SWITCHES"
}
running() { k get deployment "$1" >/dev/null 2>&1; }
switches_line() {
  local line="" name
  for name in gen ss kafka metrics; do
    if is_on "$name"; then line+="$name on, "; else line+="$name off, "; fi
  done
  if cdc_running; then line+="cdc on"; else line+="cdc off"; fi
  echo "$line"
}

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

# Runs SQL from stdin in SingleStore as root; prints rows tab-separated, without column names. Root's password is
# read in the pod, from its environment (Secret singlestore-root), never on this machine's command lines.
s2() {
  MSYS_NO_PATHCONV=1 k exec -i deployment/singlestore -- sh -c 'exec singlestore -uroot -p"$ROOT_PASSWORD" -N -B' \
    2> >(grep -v -e 'getopt: command not found' -e 'Using a password on the command line' >&2)
}

# A SQL file from demo/singlestore, with the singlestore user's catalog token and the root password put in (the
# pipelines' CREDENTIALS).
s2_file() {
  sed -e "s|\${SINGLESTORE_TOKEN}|$(token_of singlestore)|g" \
    -e "s|\${SINGLESTORE_ROOT_PASSWORD}|$(secret_of singlestore-root)|g" "$REPO/demo/singlestore/$1" | s2
}

# The pipelines carry root's password as they were made with (feeds.sql): a record of which, as a hash, so a new one
# has them made again.
feeds_key() { secret_of singlestore-root | sha256sum | cut -c1-16; }
feeds_current() { [[ "$(cat "$RUN/singlestore-feeds-key" 2>/dev/null)" == "$(feeds_key)" ]]; }

# The images the last up built ($RUN/images.env, written by build), for the switches outside up: gen, ss and cdc
# start pods from them.
load_images() {
  if [[ ! -f "$RUN/images.env" ]]; then
    echo "demo/demo.sh up first: it builds the images the switches start" >&2
    exit 1
  fi
  # shellcheck source=/dev/null
  source "$RUN/images.env"
}

image_id() { docker image inspect -f '{{.Id}}' "$1" | sed 's/^sha256://' | cut -c1-12; }

# Built from this checkout on every up, so the stack never runs code older than the checkout; Gradle and
# Docker do nothing when nothing changed. SingleStore's image is tagged with the hash of the connector jar and
# its Dockerfile, so a change to either replaces the pod (and up seeds the new one); the brokerage world's with the
# hash of its classpath and its Dockerfile. The cluster never pulls: images it runs come from the local Docker engine.
build() {
  say "building the catalog server, audit table writer, maintenance worker, change-feed connector and brokerage world jars (no tests run)"
  (cd "$REPO" && ./gradlew -q :catalog-server:bootJar :catalog-audit-tables:bootJar :catalog-maintenance:bootJar \
    :singlestore-change-feed:connectorJar :demo-datagen:imageLib)
  local jar="$REPO/singlestore-change-feed/build/libs/nuna-changefeed-connector.jar"
  rm -rf "$RUN/images"
  mkdir -p "$RUN/images/singlestore" "$RUN/images/lake/lib" "$RUN/images/brokerage"
  cp "$jar" "$RUN/images/singlestore/"
  unzip -q -j "$jar" 'lib/*' -d "$RUN/images/lake/lib"
  cp "$REPO/demo/LakeWriter.java" "$RUN/images/lake/"
  cp -r "$REPO/demo-datagen/build/image/lib" "$RUN/images/brokerage/"

  say "building the images: the catalog, the audit table writer, the maintenance worker, SingleStore with the connector, the lake writer, CloudBeaver, the brokerage world"
  docker build -q -f "$REPO/deploy/image/Dockerfile" -t nuna-catalog:demo "$REPO/catalog-server/build/libs" >/dev/null
  docker build -q -f "$REPO/deploy/image/audit-tables.Dockerfile" -t nuna-audit-tables:demo \
    "$REPO/catalog-audit-tables/build/libs" >/dev/null
  docker build -q -f "$REPO/deploy/image/maintenance.Dockerfile" -t nuna-maintenance:demo \
    "$REPO/catalog-maintenance/build/libs" >/dev/null
  # Built once: its Dockerfile downloads Iceberg's jars from Maven Central.
  if ! docker image inspect "$SPARK_IMAGE" >/dev/null 2>&1; then
    say "building the Spark image $SPARK_IMAGE for the compaction Jobs (once)"
    docker build -q -t "$SPARK_IMAGE" "$REPO/catalog-tests-engines/images/spark" >/dev/null
  fi
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
  BROKERAGE_IMAGE="nuna-demo-brokerage:$( (cat "$STACK/brokerage.Dockerfile"; cd "$RUN/images/brokerage" \
    && find lib -type f | LC_ALL=C sort | xargs sha256sum) | sha256sum | cut -c1-12)"
  docker build -q -f "$STACK/brokerage.Dockerfile" -t "$BROKERAGE_IMAGE" "$RUN/images/brokerage" >/dev/null
  BROKERAGE_IMAGE_ID=$(image_id "$BROKERAGE_IMAGE")
  say "building the Kafka Connect image (Debezium 3.7.1, the Apache Iceberg 1.12.0 sink)"
  build_connect
  printf 'SINGLESTORE_IMAGE=%s\nCLOUDBEAVER_IMAGE=%s\nCONNECT_IMAGE=%s\nBROKERAGE_IMAGE=%s\nBROKERAGE_IMAGE_ID=%s\n' \
    "$SINGLESTORE_IMAGE" "$CLOUDBEAVER_IMAGE" "$CONNECT_IMAGE" "$BROKERAGE_IMAGE" "$BROKERAGE_IMAGE_ID" > "$RUN/images.env"
  # The commit the images are built from, which print_access names (backlog 274).
  commit_here > "$RUN/built-commit.txt"

  local image
  for image in "$(image_of postgres.image)" "$(image_of kafka.image)" "$(image_of silo.image)" busybox:1.37.0 \
      "$KAFKA_UI_IMAGE" "$(image_of keycloak.image)" "$TRINO_IMAGE" "$OAUTH2_PROXY_IMAGE" "$NGINX_IMAGE"; do
    docker image inspect "$image" >/dev/null 2>&1 || docker pull -q "$image" >/dev/null
  done
}

mint() {
  "$JAVA" "$REPO/demo/Mint.java" "$RUN"
}

# The issuer's public keys (served by Service idp), the lake writer's token, the brokerage world's, Trino's own, the
# people's for their CloudBeaver connections, and cdc's for Kafka Connect's Iceberg sinks.
publish_tokens() {
  k create configmap idp-keys --from-file=keys.json="$RUN/idp/keys.json" --dry-run=client -o yaml | k apply -f - >/dev/null
  k create secret generic demo-lake-token --from-literal=token="$(token_of lake)" --dry-run=client -o yaml | k apply -f - >/dev/null
  k create secret generic demo-brokerage-token --from-literal=token="$(token_of brokerage)" --dry-run=client -o yaml \
    | k apply -f - >/dev/null
  k create secret generic demo-trino-token --from-literal=token="$(token_of trino)" --dry-run=client -o yaml | k apply -f - >/dev/null
  k create secret generic demo-people-tokens --from-literal=ana="$(token_of ana)" --from-literal=gary="$(token_of gary)" \
    --from-literal=stella="$(token_of stella)" --from-literal=oscar="$(token_of oscar)" \
    --from-literal=presenter="$(token_of presenter)" --dry-run=client -o yaml \
    | k apply -f - >/dev/null
  k create secret generic demo-cdc-token --from-literal=token="$(token_of cdc)" --dry-run=client -o yaml | k apply -f - >/dev/null
}

# Keycloak's realm signing key and certificate, CloudBeaver's client and cookie secrets and the client secrets of
# the audit table writer, the maintenance worker and Silo's console, made once per machine in $RUN/keycloak and kept, and published as
# Secret keycloak-keys (keycloak.yaml, cloudbeaver.yaml, singlestore.yaml, silo-signin.yaml; audit_writer passes
# the writer its secret). The key is fixed
# rather than Keycloak's own, made at each start, because SingleStore checks Studio's SAML sign-ins against its
# certificate, which it reads only as it starts.
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
  # Made apart from the rest: the folder predates it on machines that ran the demo before.
  [[ -f "$dir/audit-tables-client-secret" ]] || openssl rand -hex 24 > "$dir/audit-tables-client-secret"
  [[ -f "$dir/silo-client-secret" ]] || openssl rand -hex 24 > "$dir/silo-client-secret"
  [[ -f "$dir/maintenance-client-secret" ]] || openssl rand -hex 24 > "$dir/maintenance-client-secret"
  # The token the catalog sends with its audit log to the audit table writer's OTLP receiver (D23, amended
  # 2026-11-23), which asks for at least 32 characters.
  [[ -f "$dir/audit-otlp-token" ]] || openssl rand -hex 32 > "$dir/audit-otlp-token"
  # The realm file takes the key and certificate without their PEM lines.
  pem_body() { sed '/^-----/d' "$1" | tr -d '\r\n'; }
  k create secret generic keycloak-keys \
    --from-literal=realm-private-key="$(pem_body "$dir/realm-private-key.pem")" \
    --from-literal=realm-certificate="$(pem_body "$dir/realm-certificate.pem")" \
    --from-file=realm-certificate-pem="$dir/realm-certificate.pem" \
    --from-literal=cloudbeaver-client-secret="$(tr -d '\r\n' < "$dir/cloudbeaver-client-secret")" \
    --from-literal=cloudbeaver-cookie-secret="$(tr -d '\r\n' < "$dir/cloudbeaver-cookie-secret")" \
    --from-literal=audit-tables-client-secret="$(tr -d '\r\n' < "$dir/audit-tables-client-secret")" \
    --from-literal=silo-client-secret="$(tr -d '\r\n' < "$dir/silo-client-secret")" \
    --from-literal=maintenance-client-secret="$(tr -d '\r\n' < "$dir/maintenance-client-secret")" \
    --from-literal=audit-otlp-token="$(tr -d '\r\n' < "$dir/audit-otlp-token")" \
    --dry-run=client -o yaml | k apply -f - >/dev/null
  # Without their newlines, as helm's --set-file passes a file whole (audit_writer, maintenance_worker).
  local name value
  for name in audit-tables-client-secret maintenance-client-secret audit-otlp-token; do
    value=$(tr -d '\r\n' < "$dir/$name")
    [[ "$(wc -c < "$dir/$name")" == "${#value}" ]] || printf '%s' "$value" > "$dir/$name"
  done
}

# A value from $SECRETS, and a person's password from $PEOPLE_FILE.
secret_of() { tr -d '\r\n' < "$SECRETS/$1"; }
password_of() { secret_of "person-$1"; }

# A password a person can type: three groups of four lower-case letters (56 bits), which every image here takes,
# Oracle's quoted identifiers among them.
typeable() {
  local h out=""
  for h in $(openssl rand -hex 12 | tr -d '\r\n' | sed 's/../& /g'); do
    out+=$(printf "\\$(printf '%03o' $((97 + 16#$h % 26)))")
  done
  printf '%s' "${out:0:4}-${out:4:4}-${out:8:4}"
}

# Every password and key of the demo but Keycloak's (publish_keycloak_keys) and the database certificates' (db_tls),
# made once per machine in $SECRETS, one file each without a newline, and kept by down and wipe (backlog 282): none
# is in the repository, so the repository's never sign anyone in.
#   person-<name>      each person's password, in Keycloak and in the CDC leg's databases; up lists them in
#                      $PEOPLE_FILE, which the README names instead of the passwords
#   keycloak-admin     Keycloak's administrator, admin (master realm)
#   grafana-admin      Grafana's administrator, admin
#   silo-root          Silo's root key, access key nunatest
#   silo-maintenance   the maintenance worker's Silo key, user nuna-maintenance
#   singlestore-root   SingleStore's root
#   connect            Kafka Connect's REST API on 8186, user operator (backlog 283); connect.curlrc beside it gives
#                      it to curl -K, connect.htpasswd to the front door
#   backoffice         the CDC leg's PostgreSQL owner, backoffice, which Debezium signs in as
#   oracle, treasury   the CDC leg's Oracle SYS and SYSTEM, and its schema user treasury
#   debezium           Debezium's own users in the CDC leg's Oracle (c##dbzuser) and CockroachDB (debezium)
# Published as Secrets demo-secrets (the passwords and cloudbeaver-users, for keycloak.yaml, cloudbeaver.yaml,
# metrics.yaml, cdc.yaml and connect.yaml), s3-root (store-silo.yaml, seed.yaml), singlestore-root (singlestore.yaml,
# brokerage.yaml) and connect-auth (ports.yaml): every value from a file, none on a command line.
demo_secrets() {
  local name person users=""
  mkdir -p "$SECRETS"
  for name in keycloak-admin grafana-admin silo-root oracle treasury debezium \
      $(printf 'person-%s ' "${PEOPLE[@]}"); do
    [[ -s "$SECRETS/$name" ]] || typeable > "$SECRETS/$name"
  done
  for name in silo-maintenance singlestore-root connect backoffice; do
    [[ -s "$SECRETS/$name" ]] || openssl rand -hex 24 | tr -d '\r\n' > "$SECRETS/$name"
  done
  printf 'user = "operator:%s"\n' "$(secret_of connect)" > "$SECRETS/connect.curlrc"
  [[ -s "$SECRETS/connect.htpasswd" ]] \
    || printf 'operator:%s\n' "$(openssl passwd -apr1 -stdin < "$SECRETS/connect" | tr -d '\r\n')" > "$SECRETS/connect.htpasswd"
  for person in "${PEOPLE[@]}"; do
    [[ "$person" == oscar ]] || users+="${users:+ }$person:$(password_of "$person")"
  done
  printf '%s' "$users" > "$SECRETS/cloudbeaver-users"
  {
    echo "# The demo's people and their passwords, made once on this machine by demo/demo.sh up (backlog 282)."
    echo "# Each signs in through Keycloak, and to the CDC leg's databases, with the same password."
    for person in "${PEOPLE[@]}"; do printf '%-10s %s\n' "$person" "$(password_of "$person")"; done
  } > "$PEOPLE_FILE"
  local args=()
  for name in keycloak-admin grafana-admin oracle treasury debezium backoffice cloudbeaver-users \
      $(printf 'person-%s ' "${PEOPLE[@]}"); do
    args+=("--from-file=$name=$SECRETS/$name")
  done
  k create secret generic demo-secrets "${args[@]}" --dry-run=client -o yaml | k apply -f - >/dev/null
  k create secret generic s3-root --from-file=secret-key="$SECRETS/silo-root" --dry-run=client -o yaml \
    | k apply -f - >/dev/null
  k create secret generic singlestore-root --from-file=password="$SECRETS/singlestore-root" --dry-run=client -o yaml \
    | k apply -f - >/dev/null
  k create secret generic connect-auth --from-file=htpasswd="$SECRETS/connect.htpasswd" --dry-run=client -o yaml \
    | k apply -f - >/dev/null
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

# The catalog signs in to its PostgreSQL by a client certificate, over TLS, and no PostgreSQL role has a password
# (backlog 281; stack.yaml). The files, in $DB_TLS, kept by down and wipe:
#   ca.crt, ca.key          a database CA of the demo's own, made once per machine: only PostgreSQL and the catalog
#                           trust it, and only for this connection
#   server.crt, server.key  PostgreSQL's certificate, for Service postgres by each name it has in namespace $NS
#   client.crt, client.key  the catalog's, common name catalog, the user PostgreSQL signs it in as (clientAuth)
#   client.pk8              the client key again, as PKCS #8 DER, the form the JDBC driver reads
#   initdb-password         the password PostgreSQL's image asks for: random, and never used (db_no_passwords)
# Each certificate is made again a month before its end, as publish_tls makes the front door's, and a new one replaces
# PostgreSQL's pod and the catalog's (db_tls_hash). Published as Secret postgres-tls, for PostgreSQL, and Secret
# catalog-db-tls, for the catalog (the chart's databaseTls). The web pages' certificate cannot do this job: it proves
# the host's name, and a client certificate has to come from a CA the database trusts. The demo keeps its own at work
# too; nuna's own deployment takes work's PKI instead (docs/install-at-work.md).
db_tls() {
  local dir=$DB_TLS
  mkdir -p "$dir"
  # In the folder, with bare file names, as in publish_keycloak_keys.
  if [[ ! -f "$dir/ca.key" || ! -f "$dir/ca.crt" ]]; then
    say "making the database's CA, in $dir"
    (cd "$dir" && MSYS_NO_PATHCONV=1 openssl req -quiet -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 \
      -nodes -days 3650 -subj "/CN=nuna demo database CA" -addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
      -addext "keyUsage=critical,keyCertSign,cRLSign" -keyout ca.key -out ca.crt 2>/dev/null)
    rm -f "$dir/server.crt" "$dir/client.crt"
  fi
  if ! (cd "$dir" && openssl x509 -checkend 2592000 -noout -in server.crt >/dev/null 2>&1 \
      && openssl x509 -checkhost "postgres.$NS.svc" -noout -in server.crt 2>/dev/null | grep -q 'does match'); then
    say "making PostgreSQL's certificate, for Service postgres in namespace $NS"
    (cd "$dir" && MSYS_NO_PATHCONV=1 openssl req -quiet -x509 -CA ca.crt -CAkey ca.key -newkey ec \
      -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 397 -subj "/CN=postgres" \
      -addext "subjectAltName=DNS:postgres,DNS:postgres.$NS.svc,DNS:postgres.$NS.svc.cluster.local" \
      -addext "extendedKeyUsage=serverAuth" -addext "basicConstraints=critical,CA:FALSE" \
      -keyout server.key -out server.crt 2>/dev/null)
  fi
  if ! (cd "$dir" && openssl x509 -checkend 2592000 -noout -in client.crt >/dev/null 2>&1 && [[ -f client.pk8 ]]); then
    say "making the catalog's client certificate for its database, common name catalog"
    (cd "$dir" && MSYS_NO_PATHCONV=1 openssl req -quiet -x509 -CA ca.crt -CAkey ca.key -newkey ec \
      -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 397 -subj "/CN=catalog" \
      -addext "extendedKeyUsage=clientAuth" -addext "basicConstraints=critical,CA:FALSE" \
      -keyout client.key -out client.crt 2>/dev/null)
    (cd "$dir" && openssl pkcs8 -topk8 -nocrypt -in client.key -outform DER -out client.pk8)
  fi
  [[ -f "$dir/initdb-password" ]] || openssl rand -hex 24 | tr -d '\r\n' > "$dir/initdb-password"
  k create secret generic postgres-tls --from-file=server.crt="$dir/server.crt" \
    --from-file=server.key="$dir/server.key" --from-file=ca.crt="$dir/ca.crt" \
    --from-file=initdb-password="$dir/initdb-password" --dry-run=client -o yaml | k apply -f - >/dev/null
  k create secret generic catalog-db-tls --from-file=ca.crt="$dir/ca.crt" --from-file=client.crt="$dir/client.crt" \
    --from-file=client.pk8="$dir/client.pk8" --dry-run=client -o yaml | k apply -f - >/dev/null
}

# Changes whenever db_tls makes a certificate: PostgreSQL (stack.yaml) and the catalog (catalog_release) carry it as a
# pod annotation, so a new certificate replaces both pods. They read their files only as they start.
db_tls_hash() { cat "$DB_TLS/ca.crt" "$DB_TLS/server.crt" "$DB_TLS/client.crt" 2>/dev/null | sha256sum | cut -c1-12; }

# No PostgreSQL role has a password (backlog 281): PostgreSQL's image made role catalog with initdb-password, and a
# volume from before 281 keeps the one the catalog signed in with then. Taking it away again changes nothing.
db_no_passwords() {
  k exec deployment/postgres -- psql -q -X -v ON_ERROR_STOP=1 -U catalog -d catalog -c 'ALTER ROLE catalog PASSWORD NULL'
}

# The database's sign-in as backlog 281 sets it, checked on every up once the catalog is serving: PostgreSQL reads the
# stack's pg_hba.conf; a network connection without a client certificate is refused, over TLS and without it; no role
# has a password; and every network connection, which only the catalog makes, is TLS, signed in as CN=catalog. Says
# what it found; returns 1 at its first finding.
db_check() {
  local out mode want total signed versions
  db_sql() { k exec deployment/postgres -- psql -X -w -U catalog -d catalog -tAc "$1" | tr -d '\r'; }
  out=$(db_sql 'SHOW hba_file') || return 1
  if [[ "$out" != /etc/postgres-hba/pg_hba.conf ]]; then
    echo "PostgreSQL reads its connection rules from $out, not from ConfigMap postgres-hba" >&2
    return 1
  fi
  for mode in require disable; do
    if [[ $mode == require ]]; then want='requires a valid client certificate'; else want='no pg_hba.conf entry'; fi
    if out=$(k exec deployment/postgres -- psql -X -w "host=127.0.0.1 dbname=catalog user=catalog sslmode=$mode" \
        -tAc 'SELECT 1' 2>&1); then
      echo "PostgreSQL let in a network connection without a client certificate (sslmode=$mode)" >&2
      return 1
    fi
    if [[ "$out" != *"$want"* ]]; then
      echo "a network connection without a client certificate (sslmode=$mode) failed, but not as expected: $out" >&2
      return 1
    fi
  done
  out=$(db_sql "SELECT string_agg(rolname, ', ') FROM pg_authid WHERE rolpassword IS NOT NULL") || return 1
  if [[ -n "$out" ]]; then
    echo "PostgreSQL roles with a password: $out" >&2
    return 1
  fi
  out=$(db_sql "SELECT count(*), count(*) FILTER (WHERE s.ssl AND s.client_dn = '/CN=catalog'),
      coalesce(string_agg(DISTINCT s.version, ','), '-')
    FROM pg_stat_activity a JOIN pg_stat_ssl s USING (pid) WHERE a.client_addr IS NOT NULL") || return 1
  IFS='|' read -r total signed versions <<<"$out"
  if [[ "$total" == 0 || "$total" != "$signed" ]]; then
    echo "of PostgreSQL's $total network connections, $signed are TLS signed in as CN=catalog" >&2
    return 1
  fi
  say "the catalog's database: its $total network connections are $versions signed in as CN=catalog; one without a client certificate is refused; no role has a password"
}

# Expired, about to expire, or minted before Mint.java last changed (a user or group added).
tokens_expired() {
  [[ ! -f "$RUN/tokens/expires.txt" || ! -f "$RUN/tokens/trino.jwt" \
    || "$REPO/demo/Mint.java" -nt "$RUN/tokens/expires.txt" ]] && return 0
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
  for port in 8180 8181 8182 8183 8184 8185 8186 8187 8188; do
    if (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then busy+=("$port"); fi
  done
  if (( ${#busy[@]} )); then
    echo "localhost port(s) ${busy[*]} are in use; the demo publishes 8180 to 8188. Stop what holds them." >&2
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
# demo-grant-admins and oscar's demo-operators may hold no grant at all, so they hold none. The maintenance worker's
# demo-maintenance holds write on lakehouse, which it maintains (D30). presenter's demo-presenters holds every
# privilege on every catalog, the audit tables' included, against the separation of duties (Paul, 2026-11-25: D18
# amended; backlog 273).
grants() {
  say "granting the demo groups and the engine matrix their privileges (by SQL)"
  k exec -i deployment/postgres -- psql -q -v ON_ERROR_STOP=1 -U catalog -d catalog <<'SQL'
INSERT INTO grant_record (catalog_id, securable_kind, securable_id, group_name, privilege, granted_by, granted_at)
SELECT c.id, 'CATALOG', c.id, g.group_name, g.privilege, 'demo-setup', now()
FROM catalog c,
     (VALUES ('lakehouse', 'demo-loaders', 'create'), ('lakehouse', 'demo-loaders', 'modify'),
             ('lakehouse', 'demo-analysts', 'create'), ('lakehouse', 'demo-analysts', 'modify'),
             ('lakehouse', 'demo-stewards', 'manage-tags'), ('lakehouse', 'demo-stewards', 'select'),
             ('lakehouse', 'demo-maintenance', 'write'),
             ('engines', 'engine-clients', 'create'), ('engines', 'engine-clients', 'modify'),
             ('engines', 'demo-analysts', 'create'), ('engines', 'demo-analysts', 'modify'),
             ('engines', 'demo-stewards', 'select'),
             ('lakehouse', 'demo-presenters', 'create'), ('lakehouse', 'demo-presenters', 'modify'),
             ('lakehouse', 'demo-presenters', 'manage-tags'),
             ('engines', 'demo-presenters', 'create'), ('engines', 'demo-presenters', 'modify'),
             ('engines', 'demo-presenters', 'manage-tags'),
             ('nuna_audit', 'demo-presenters', 'create'), ('nuna_audit', 'demo-presenters', 'modify'),
             ('nuna_audit', 'demo-presenters', 'manage-tags')) AS g(catalog_name, group_name, privilege)
WHERE c.name_norm = g.catalog_name
ON CONFLICT DO NOTHING;
SQL
}

# Trino's own client, trino-demo (Mint.java), registered as a trusted engine (D17) by oscar, a platform operator.
# Its group holds no grant, so only as a registered engine does its own token see the catalogs and read their
# config (D18, item 84); it still sees nothing in them. On every up, before Trino starts: the registry goes with
# PostgreSQL's pod, and registering it again leaves the registry as it was. Registered, it vouches for the user of
# each query, whose own token the query carries (trino.yaml), so a flagged table's data reaches them through Trino
# with their row filter and column masks applied (D20, backlog 115).
trusted_engines() {
  say "registering Trino's client, trino-demo, as a trusted engine (as oscar, the platform operator)"
  api oscar PUT /management/v1/engines '{"issuer": "https://idp.demo/nuna", "client-id": "trino-demo"}' 204
}

# Keycloak's realm (keycloak-realm.json) and Keycloak: a changed realm replaces its pod, which imports it as it starts.
keycloak_realm() {
  fill keycloak-realm.json > "$RUN/keycloak-realm.json"
  k create configmap keycloak-realm --from-file=nuna.json="$RUN/keycloak-realm.json" --dry-run=client -o yaml \
    | k apply -f - >/dev/null
  apply keycloak.yaml
}

# The catalog (chart deploy/helm/nuna-catalog), installed or brought up to date. The image's id as a pod annotation:
# a rebuilt image under the same tag rolls the server, an unchanged one does not; the database certificates' hash
# likewise (db_tls).
catalog_release() {
  fill demo-values.yaml > "$RUN/demo-values.yaml"
  # shellcheck disable=SC2046 # telemetry_values prints flags, one a line, none with spaces
  helm --kube-context "$CONTEXT" -n "$NS" upgrade --install "$RELEASE" "$REPO/deploy/helm/nuna-catalog" \
    -f "$STACK/catalog-values.yaml" -f "$RUN/demo-values.yaml" $(telemetry_values) \
    --set-string "podAnnotations.nuna-demo/image=$(image_id nuna-catalog:demo)" \
    --set-string "podAnnotations.nuna-demo/db-tls=$(db_tls_hash)" --wait --timeout 10m >/dev/null
}

# The catalog's and the worker's telemetry while the metrics stack runs (backlog 233): metrics and traces to the
# collector, every trace kept (the demo's traffic is light), and the logs where they went before (the catalog's to
# the audit table writer; the worker's to stdout, and the collector drops the copy it sends). Otherwise nothing:
# the charts' default, no endpoint, exports nothing.
telemetry_values() {
  if is_on metrics && running otel-collector; then
    printf '%s\n' --set-string telemetry.endpoint=http://otel-collector:4318 --set-string telemetry.sampling=1
  fi
}

# A Kubernetes memory quantity in MiB.
mib() {
  local v=$1
  case "$v" in
    *Ki) echo $(( ${v%Ki} / 1024 )) ;;
    *Mi) echo "${v%Mi}" ;;
    *Gi) echo $(( ${v%Gi} * 1024 )) ;;
    *k) echo $(( ${v%k} * 1000 / 1048576 )) ;;
    *M) echo $(( ${v%M} * 1000000 / 1048576 )) ;;
    *G) echo $(( ${v%G} * 1000000000 / 1048576 )) ;;
    *) echo $(( v / 1048576 )) ;;
  esac
}
# The memory Docker Desktop's node has that no pod asks for, in MiB: what a new pod can be given.
free_mib() {
  local allocatable requested
  allocatable=$(kubectl --context "$CONTEXT" get nodes -o jsonpath='{.items[0].status.allocatable.memory}')
  requested=$(kubectl --context "$CONTEXT" describe nodes \
    | awk '/^Allocated resources/ { found = 1 } found && $1 == "memory" { print $2; exit }')
  echo $(( $(mib "$allocatable") - $(mib "$requested") ))
}
# Fails, saying so, when the node has less than $1 MiB free for $2; $3 says what to turn off.
room_for() {
  local free
  free=$(free_mib)
  if (( free < $1 )); then
    echo "$2 asks for $1 MiB of Docker Desktop's memory, and only $free MiB is free: $3" >&2
    return 1
  fi
}

# The metrics stack (metrics.yaml), applied with the alert rules and dashboards in deploy/grafana as ConfigMaps, and
# waited for. Its images are pulled once.
metrics_apply() {
  local image deployment
  say "starting the metrics stack: the OpenTelemetry collector, Prometheus, Tempo and Grafana"
  for image in "$OTEL_COLLECTOR_IMAGE" "$PROMETHEUS_IMAGE" "$TEMPO_IMAGE" "$GRAFANA_IMAGE"; do
    docker image inspect "$image" >/dev/null 2>&1 || docker pull -q "$image" >/dev/null
  done
  k create configmap prometheus-rules --from-file="$REPO/deploy/grafana/rules" --dry-run=client -o yaml \
    | k apply -f - >/dev/null
  k create configmap grafana-dashboards --from-file="$REPO/deploy/grafana/dashboards" --dry-run=client -o yaml \
    | k apply -f - >/dev/null
  apply metrics.yaml
  for deployment in otel-collector prometheus tempo grafana; do
    k rollout status "deployment/$deployment" --timeout=5m >/dev/null
  done
  # Grafana takes its administrator's password from the environment only when it makes its database, and keeps
  # its database on a volume: set again, from stdin, so a kept one takes the password made on this machine
  # (backlog 282). Setting the same one again changes nothing.
  k exec -i deployment/grafana -- grafana cli admin reset-admin-password --password-from-stdin \
      < "$SECRETS/grafana-admin" >/dev/null \
    || say "Grafana's administrator password was not set (see above); the next metrics or up tries again (backlog 282)"
}

# The switch metrics (backlog 233) turned on: the stack, Keycloak's client grafana (a realm from before it replaces
# Keycloak's pod), and the catalog and the worker sending to it. Refused when its pods would not fit: on an 8 GB Docker
# Desktop it fits beside gen and kafka, or ss, not beside gen, kafka and ss together. With the demo down, the switch
# is set and up starts it. While the CDC leg runs, the worker is stopped, and cdc stop starts it sending.
metrics_start() {
  if ! running "$RELEASE"; then
    set_switch metrics on
    say "the demo is down: the metrics stack starts with demo/demo.sh up"
    return 0
  fi
  if ! running otel-collector; then
    room_for $((METRICS_MIB + CATALOG_MIB)) "the metrics stack, with the catalog's new pod while it rolls" \
      "turn a switch off first (demo/demo.sh ss stop frees 1.5 GB)" || exit 1
  fi
  set_switch metrics on
  keycloak_realm
  k rollout status deployment/keycloak --timeout=5m >/dev/null
  metrics_apply
  # Grafana's port, 8188, on a front door from before it (which replaces the door's pod once).
  apply ports.yaml
  k rollout status deployment/front --timeout=2m >/dev/null
  say "pointing the catalog at the collector"
  catalog_release
  if cdc_running; then
    say "the CDC leg is running: the maintenance worker sends to the collector once demo/demo.sh cdc stop starts it"
  else
    maintenance_worker || say "the maintenance worker was not pointed at the collector (see above); the next up tries again"
  fi
}

# The switch metrics turned off: the stack goes first, so its memory is there for the catalog's new pod, then the
# catalog and the worker stop sending (their exports fail meanwhile, a warning a minute). Its series, traces and
# Grafana's database stay on their volumes for the next metrics.
metrics_stop() {
  local was=
  if running otel-collector; then
    was=yes
  fi
  set_switch metrics off
  [[ -n "$was" ]] || return 0
  say "stopping the metrics stack (its data stays on its volumes for demo/demo.sh metrics)"
  unapply metrics.yaml
  k delete configmap prometheus-rules grafana-dashboards --ignore-not-found >/dev/null
  k wait --for=delete pod -l 'app in (otel-collector, prometheus, tempo, grafana)' --timeout=2m >/dev/null 2>&1 || true
  if running "$RELEASE"; then
    say "pointing the catalog away from the collector"
    catalog_release
    if ! cdc_running; then
      maintenance_worker || say "the maintenance worker was not pointed away from the collector (see above)"
    fi
  fi
}

# The audit table writer (D23; chart deploy/helm/nuna-audit-tables, audit-tables-values.yaml), installed or brought
# up to date: it writes the audit topic to nuna_audit.audit.events, which the console's Audit page reads. It signs
# in to Keycloak as client nuna-audit-tables, with the secret publish_keycloak_keys made. The image's id as a pod
# annotation, as for the catalog. One replica: an upgrade also starts it again after cdc stopped it.
audit_writer() {
  say "starting the audit table writer (chart deploy/helm/nuna-audit-tables), for the console's Audit page"
  helm --kube-context "$CONTEXT" -n "$NS" upgrade --install nuna-audit-tables "$REPO/deploy/helm/nuna-audit-tables" \
    -f "$STACK/audit-tables-values.yaml" \
    --set-file "secrets.values.nuna\.audit-tables\.token\.client-secret=$RUN/keycloak/audit-tables-client-secret" \
    --set-file "secrets.values.nuna\.audit-tables\.otlp\.token=$RUN/keycloak/audit-otlp-token" \
    --set-string "podAnnotations.nuna-demo/image=$(image_id nuna-audit-tables:demo)" --wait --timeout 5m >/dev/null
}

# The worker's Silo policy, as a platform operator applies it at work (deploy/README.md, "Delete rights"; backlog 255):
# the `to` policy the catalog generates for each profile naming nuna-maintenance as its maintenance principal
# (demo-values.yaml's s3), read as oscar from GET /management/v1/maintenance/storage-setup, made under its own name
# (nuna-worker-<profile>) and attached to user nuna-maintenance. Making it again replaces it with the catalog's text,
# so changed prefixes take effect. The `except` policy, for every other user, is not applied: the demo's catalog signs
# in as Silo's root, nunatest, whom no policy binds (demo/README.md, "Maintenance"). Returns at its first failure.
worker_policy() {
  local setup entries entry name policy
  setup=$(tls_curl -fsS -H "Authorization: Bearer $(token_of oscar)" "$URL/management/v1/maintenance/storage-setup") \
    || return 1
  # Compact JSON (Jackson), each MinIO `to` entry's fields in this order; the policy texts hold no backslash but the
  # quotes' escapes (DeletePolicy).
  entries=$(grep -oE '"to":"nuna-maintenance","name":"nuna-worker-[A-Za-z0-9._-]+","file":"[^"]+","policy":"([^"\\]|\\.)*"' \
    <<<"$setup") || { echo "the catalog generates no Silo policy for nuna-maintenance: $setup" >&2; return 1; }
  while IFS= read -r entry; do
    name=$(sed -E 's/^.*"name":"([^"]+)".*$/\1/' <<<"$entry")
    policy=$(sed -E -e 's/^.*"policy":"(.*)"$/\1/' -e 's/\\"/"/g' <<<"$entry")
    k exec -i deployment/s3 -c silo -- sh -c "cat > /tmp/$name.json &&
      mc alias set l http://127.0.0.1:9000 \"\$MINIO_ROOT_USER\" \"\$MINIO_ROOT_PASSWORD\" >/dev/null &&
      mc admin policy create l $name /tmp/$name.json >/dev/null &&
      { mc admin policy attach l $name --user nuna-maintenance >/dev/null 2>&1 || true; }" <<<"$policy" || return 1
  done <<<"$entries"
  # The hand-written policy up attached before backlog 255, still on a Silo volume kept since: the worker holds only
  # the `to` policies (deploy/README.md).
  k exec deployment/s3 -c silo -- sh -c 'mc alias set l http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null &&
    { mc admin policy detach l nuna-maintenance --user nuna-maintenance >/dev/null 2>&1 || true; } &&
    { mc admin policy rm l nuna-maintenance >/dev/null 2>&1 || true; }'
}

# The maintenance worker (D30; chart deploy/helm/nuna-maintenance, maintenance-values.yaml), installed or brought up to
# date, with its compaction Jobs' namespace (maintenance-jobs.yaml) and its own Silo user, nuna-maintenance, holding the
# policy the catalog generates (worker_policy). Silo keeps the user on its volume, and every up makes it again with its
# key, so a changed key takes effect. The catalog must be serving: up starts the worker after wait_for_console. It
# signs in to Keycloak as client nuna-maintenance, with the secret publish_keycloak_keys made. The image's id as a pod
# annotation, as for the catalog. One replica: an upgrade also starts it again after cdc stopped it.
maintenance_worker() {
  say "starting the maintenance worker (chart deploy/helm/nuna-maintenance), its compaction Jobs in namespace $NS-compaction"
  k exec -i deployment/s3 -c silo -- sh -c 'mc alias set l http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null &&
    { read -r key || [ -n "$key" ]; } && mc admin user add l nuna-maintenance "$key" >/dev/null' < "$SECRETS/silo-maintenance"
  worker_policy || say "the worker's Silo policy was not applied (see above): its scans may be refused, and readiness says so; the next up tries again (backlog 255)"
  fill maintenance-jobs.yaml | kubectl --context "$CONTEXT" apply -f - >/dev/null
  fill maintenance-values.yaml > "$RUN/maintenance-values.yaml"
  helm --kube-context "$CONTEXT" -n "$NS" upgrade --install nuna-maintenance "$REPO/deploy/helm/nuna-maintenance" \
    -f "$RUN/maintenance-values.yaml" \
    --set-file "secrets.values.nuna\.maintenance\.token\.client-secret=$RUN/keycloak/maintenance-client-secret" \
    --set-file "secrets.values.nuna\.maintenance\.storage\.s3\.secret-access-key=$SECRETS/silo-maintenance" \
    $(telemetry_values) \
    --set-string "podAnnotations.nuna-demo/image=$(image_id nuna-maintenance:demo)" --wait --timeout 5m >/dev/null
}

# Stops the maintenance worker, if it runs, and any compaction Job still running, for memory something else needs
# (cdc, engines). A Job stopped part way keeps the file groups it committed; the worker compacts that table again
# when it starts.
stop_maintenance() {
  local job
  if running nuna-maintenance; then
    k scale deployment/nuna-maintenance --replicas=0 >/dev/null
    k wait --for=delete pod -l app.kubernetes.io/instance=nuna-maintenance --timeout=2m >/dev/null 2>&1 || true
  fi
  for job in $(kubectl --context "$CONTEXT" -n "$NS-compaction" get jobs \
      -o jsonpath='{range .items[?(@.status.active)]}{.metadata.name}{" "}{end}' 2>/dev/null); do
    kubectl --context "$CONTEXT" -n "$NS-compaction" delete job "$job" --wait=true >/dev/null
  done
}

# Catalog lakehouse's maintenance policy (D30), as ana, who may modify it: every job on, snapshots expired once an
# hour old (the least the catalog allows), keeping the newest 5 of each table; the other keys as built in. The
# database is new at each up, so every up sets it. Branches, tags, holds and freezes keep what they keep.
maintenance_policy() {
  say "turning maintenance on in $CATALOG's policy (as ana)"
  api ana PUT "/management/v1/catalogs/$CATALOG/maintenance-policy" '{"snapshot-expiry.enabled": "true",
    "snapshot-expiry.max-snapshot-age": "PT1H", "snapshot-expiry.min-snapshots-to-keep": "5",
    "orphan-removal.enabled": "true", "compaction.enabled": "true", "manifest-rewrite.enabled": "true"}'
}

# Waits for a Job to end: prints its output, deletes it, fails if it failed.
finish_job() {
  local job=$1 what=$2 succeeded= failed=
  for _ in $(seq 1 300); do
    succeeded=$(k get job "$job" -o jsonpath='{.status.succeeded}')
    failed=$(k get job "$job" -o jsonpath='{.status.failed}')
    [[ -n "$succeeded$failed" ]] && break
    sleep 2
  done
  k logs "job/$job" 2>/dev/null | grep -v '^Picked up JAVA_TOOL_OPTIONS' || true
  k delete job "$job" --wait=false >/dev/null
  if [[ "$succeeded" != 1 ]]; then
    echo "$what did not succeed" >&2
    return 1
  fi
}

# One run of the lake writer (seed, rate or dev) as a Job.
lake() {
  local mode=$1 job
  job="lake-$mode-$(date +%s)"
  sed -e "s|\${JOB}|$job|" -e "s|\${MODE}|$mode|" "$STACK/lake-job.yaml" | k apply -f - >/dev/null
  finish_job "$job" "the lake writer ($mode)"
}

# Kafka Connect and the brokerage world's generator (connect.yaml, brokerage.yaml) ask for 768 MiB between them,
# which leaves Docker Desktop's node about 390 MiB: too little for a 768 MiB Job (the lake writer), for Spark, or
# for a catalog server rolling to a new image, whose new pod starts before the old one stops. So up, rate and
# engines stop both first and start them again after; up starts them last.
pause_world() {
  local deployment
  for deployment in brokerage connect; do
    if k get deployment "$deployment" >/dev/null 2>&1; then
      k scale deployment/"$deployment" --replicas=0 >/dev/null
    fi
  done
  k wait --for=delete pod -l 'app in (brokerage, connect)' --timeout=2m >/dev/null 2>&1 || true
}

resume_world() {
  local deployment
  for deployment in connect brokerage; do
    if k get deployment "$deployment" >/dev/null 2>&1; then
      k scale deployment/"$deployment" --replicas=1 >/dev/null
    fi
  done
}

# Starts a pipeline unless it is running already.
start_pipeline() {
  local db=$1 name=$2 state
  state=$(echo "SELECT STATE FROM information_schema.PIPELINES WHERE DATABASE_NAME = '$db' AND PIPELINE_NAME = '$name';" | s2)
  if [[ "$state" != Running* ]]; then
    echo "START PIPELINE $db.$name;" | s2 >/dev/null
  fi
}

# The three running pipelines, created or replaced with the current token, and started; first the brokerage world's
# database, which its change feed reads (brokerage.sql, made if missing: older SingleStore pods have none).
feeds() {
  s2_file brokerage.sql >/dev/null
  s2_file feeds.sql >/dev/null
  start_pipeline lake fx_rates_feed
  start_pipeline ops orders_to_lake
  start_pipeline brokerage brokerage_orders_to_lake
  feeds_key > "$RUN/singlestore-feeds-key"
  if is_on gen && ! is_on kafka; then
    world_feeds_start
  fi
}

# The brokerage world's other feeds in SingleStore, for while Kafka is off (backlog 238): a table each
# (brokerage-feeds.sql, generated by demo-datagen), made whenever gen runs with ss on, since the generator counts
# their rows; and a change feed each into the lake under brokerage.singlestore (brokerage-feeds-pipelines.sql), run
# only while Kafka is off, as each is a Java extractor of its own in SingleStore's memory. The copies hold the
# party and ledger tables' rows, so their namespace is governed as those are: restricted and regulated.
WORLD_FEEDS="calendar securities chart_of_accounts booking_schemes accounts bars_5m prices_eod financial_transactions
  journal_lines posting_rejections balances_eod"
world_tables() { s2_file brokerage-feeds.sql >/dev/null; }
world_feeds_start() {
  local table m=/management/v1/catalogs/$CATALOG
  say "SingleStore: the brokerage world's feeds, a change feed per table into brokerage.singlestore (Kafka is off)"
  world_tables
  api brokerage GET "/v1/$CATALOG/namespaces/brokerage%1Fsinglestore" '' 200 2>/dev/null \
    || api brokerage POST "/v1/$CATALOG/namespaces" '{"namespace": ["brokerage", "singlestore"]}' 200 2>/dev/null \
    || api brokerage GET "/v1/$CATALOG/namespaces/brokerage%1Fsinglestore" '' 200
  api stella PUT "$m/namespaces/brokerage%1Fsinglestore/attributes/classification" \
    '{"value":"restricted","provenance":"demo-seed: data catalogue"}'
  api stella PUT "$m/namespaces/brokerage%1Fsinglestore/attributes/regulated" \
    '{"value":"true","provenance":"demo-seed: compliance register"}'
  s2_file brokerage-feeds-pipelines.sql >/dev/null
  for table in $WORLD_FEEDS; do start_pipeline brokerage "brokerage_${table}_to_lake"; done
}
world_feeds_stop() {
  local name stopped=
  running singlestore || return 0
  for name in $(echo "SELECT PIPELINE_NAME FROM information_schema.PIPELINES WHERE DATABASE_NAME = 'brokerage'
      AND PIPELINE_NAME <> 'brokerage_orders_to_lake' AND STATE = 'Running';" | s2); do
    echo "STOP PIPELINE brokerage.$name;" | s2 >/dev/null
    stopped=yes
  done
  [[ -z "$stopped" ]] || say "stopped the change feeds of the brokerage world's SingleStore tables"
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
# lake empties SingleStore too, since its databases are loaded from the lake, and the brokerage world's orders
# would no longer match its lake (feeds makes its database again).
seed() {
  local seeded= ns='markets%1Fequities%1Feu%1Fxetra'
  if k get configmap demo-seeded >/dev/null 2>&1 \
      && api ana GET "/v1/$CATALOG/namespaces/$ns/tables/trades" '' 200 2>/dev/null; then
    seeded=yes
  fi
  if [[ -z "$seeded" ]]; then
    k delete configmap demo-seeded --ignore-not-found >/dev/null
    if running singlestore; then
      echo "DROP DATABASE IF EXISTS ops; DROP DATABASE IF EXISTS lake; DROP DATABASE IF EXISTS brokerage;" | s2 >/dev/null
    fi
    say "writing the sample Iceberg tables through the catalog (demo/LakeWriter.java)"
    lake seed
  fi
  [[ -n "$seeded" ]] && return 0
  say "governance: classification, owner, a legal hold, a DR freeze"
  local m=/management/v1/catalogs/$CATALOG
  api stella PUT "$m/namespaces/markets/attributes/owner" '{"value":"markets-data-office","provenance":"demo-seed: HR feed"}'
  api stella PUT "$m/namespaces/$ns/tables/trades/attributes/classification" '{"value":"internal","provenance":"demo-seed: data catalogue"}'
  api stella POST "$m/namespaces/$ns/tables/trades/holds" '{"reason":"Matter 2026-114: trade surveillance review"}'
  api oscar PUT "$m/namespaces/reference/dr-freeze" '{"reason":"DR drill 2026-11-10: reference data frozen"}'

  say "grants through the management API, as the grant admin"
  api gary POST "$m/grants" '{"securable":{"type":"namespace","namespace":["markets"]},"group":"demo-quants","privilege":"describe"}'
  api gary POST "$m/grants" "{\"securable\":{\"type\":\"table\",\"namespace\":[\"markets\",\"equities\",\"eu\",\"xetra\"],\"name\":\"trades\"},\"group\":\"demo-quants\",\"privilege\":\"select\"}"
  k create configmap demo-seeded >/dev/null
}

# Row filters and column masks (D20, backlog 115), on markets.desks.positions, which the lake writer makes once (its
# files are written before the flag, which then lets only a trusted engine reach them). stella flags it and gary,
# the grant admin, sets each group's rule: ana's demo-analysts see the equity desk's rows with each trader's name
# masked, and stella's demo-stewards every desk's rows with the P&L hidden. In CloudBeaver each runs
#   SELECT * FROM lakehouse."markets.desks".positions
# through Trino and gets their own answer; read without Trino, its data answers 403. Every up, after seed: setting
# them again changes nothing.
row_column_rules() {
  local m=/management/v1/catalogs/$CATALOG ns='markets%1Fdesks'
  local table='{"type":"table","namespace":["markets","desks"],"name":"positions"}'
  if ! api ana HEAD "/v1/$CATALOG/namespaces/$ns/tables/positions" '' 204 2>/dev/null; then
    say "writing markets.desks.positions, the sample for row filters and column masks (demo/LakeWriter.java)"
    lake positions
  fi
  say "row filters and column masks on markets.desks.positions: flagged by stella, each group's rule set by gary (D20)"
  api stella PUT "$m/namespaces/$ns/tables/positions/attributes/row-column-policy" \
    '{"value":"true","provenance":"demo-seed: data catalogue"}' &&
  api gary POST "$m/row-column-rules" \
    "{\"table\":$table,\"group\":\"demo-analysts\",\"row-filter\":\"desk = 'EQ'\",\"column-masks\":{\"trader\":\"'***'\"}}" &&
  api gary POST "$m/row-column-rules" \
    "{\"table\":$table,\"group\":\"demo-stewards\",\"column-masks\":{\"pnl\":\"NULL\"}}"
}

# SingleStore's databases, once per volume (done when ops exists; a new or wiped volume starts empty, and seed
# empties a running one when it makes a new lake).
ss_seed() {
  if [[ -n "$(echo "SHOW DATABASES LIKE 'ops';" | s2)" ]]; then
    echo "SingleStore already holds the sample data"
    return 0
  fi
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
}

# The switch ss (D50): SingleStore, its users, its sample databases and its three running pipelines. Pipelines
# carry the token they were made with (feeds.sql), so new tokens ($1 = yes) or a pod made before the brokerage world
# (no change feed of its own) has them made again.
ss_start() {
  local minted=${1:-}
  set_switch ss on
  say "starting SingleStore ($SINGLESTORE_IMAGE)"
  apply singlestore.yaml
  k rollout status deployment/singlestore --timeout=15m >/dev/null
  s2_file users.sql >/dev/null
  ss_seed
  if [[ -n "$minted" ]] || ! feeds_current || [[ "$(echo "SELECT COUNT(*) FROM information_schema.PIPELINES WHERE DATABASE_NAME = 'brokerage';" | s2)" == 0* ]]; then
    feeds
  fi
}

# The switch ss turned off. SingleStore's databases stay on its volume (backlog 237), and ss carries on from them.
# The generator does not need it (Paul, 2026-11-24): it stops, and starts again through Kafka once the orders' change
# feed has made its last commit. With kafka off, its other feeds went into SingleStore too: their change feeds bring
# the last of them into the lake first (Main drain), and kafka goes on. While the CDC leg runs, the generator is
# stopped already, and starts through Kafka with cdc stop.
ss_stop() {
  local reroute=
  if is_on gen && ! cdc_running; then
    reroute=yes
    stop_generator
  fi
  if is_on gen && ! is_on kafka && running singlestore; then
    if ! brokerage_job drain singlestore; then
      say "the lake has not caught up with the generator's SingleStore tables: SingleStore stays on"
      [[ -z "$reroute" ]] || gen_start
      return 1
    fi
    world_feeds_stop
  fi
  if is_on gen; then
    set_switch kafka on
  fi
  set_switch ss off
  ss_down
  if [[ -n "$reroute" ]]; then
    gen_start
  elif kafka_needed; then
    start_kafka
  fi
}
ss_down() {
  running singlestore || return 0
  say "stopping SingleStore (its databases stay on its volume for demo/demo.sh ss)"
  unapply singlestore.yaml
  k wait --for=delete pod -l app=singlestore --timeout=3m >/dev/null 2>&1 || true
}

# The switch ss turned on: SingleStore, and the generator's orders back to it.
ss_on() {
  local before
  if ! running singlestore && running prometheus; then
    room_for 1536 SingleStore "demo/demo.sh metrics stop first" || exit 1
  fi
  before=$(world_route)
  ss_start
  if is_on gen && ! cdc_running && [[ "$before" != "$(world_route)" ]]; then
    stop_generator
    gen_start
  fi
}

# The switch kafka turned on (backlog 238). With the generator writing into SingleStore alone, it stops, the change
# feeds of its tables bring the last of them into the lake (Main drain), and it starts again through Kafka.
kafka_on() {
  if is_on kafka; then
    is_on gen && ! cdc_running && gen_start
    return 0
  fi
  if is_on gen && ! cdc_running; then
    stop_generator
    if running singlestore && ! brokerage_job drain singlestore; then
      say "the lake has not caught up with the generator's SingleStore tables: it carries on through SingleStore"
      gen_start
      return 1
    fi
    world_feeds_stop
    set_switch kafka on
    gen_start
  else
    set_switch kafka on
    world_feeds_stop
    say "the generator will write through Kafka: Kafka starts with it (demo/demo.sh gen, or cdc stop)"
  fi
}

# The switch kafka turned off (backlog 238), refused while the CDC leg runs, which needs Kafka. With ss on, the
# generator stops, the sink brings the last of what it sent into the lake (Main drain), Kafka stops, and the generator
# starts again with its feeds in SingleStore. With ss off, it has no way left, and gen goes off.
kafka_off() {
  if cdc_running; then
    echo "the CDC leg needs Kafka: demo/demo.sh cdc stop first" >&2
    return 1
  fi
  if ! is_on gen; then
    set_switch kafka off
    stop_kafka
    return 0
  fi
  if ! is_on ss; then
    say "the generator has no way left but Kafka with ss off: gen goes off too (demo/demo.sh ss, then gen, starts it through SingleStore)"
    gen_stop
    set_switch kafka off
    return 0
  fi
  stop_generator
  if running connect && ! brokerage_job drain kafka; then
    say "the lake has not caught up with the generator's topics: it carries on through Kafka"
    gen_start
    return 1
  fi
  set_switch kafka off
  stop_kafka
  gen_start
}

# The generator's pod alone, its switch left on, before its route changes; then the last commits of what it wrote.
stop_generator() {
  running brokerage || return 0
  say "stopping the brokerage world's generator while its route changes"
  unapply brokerage.yaml
  k wait --for=delete pod -l app=brokerage --timeout=2m >/dev/null 2>&1 || true
  sleep 20
}

# A run of the generator's image as a Job (brokerage-bootstrap.yaml, with brokerage.yaml's environment): Main's
# bootstrap, or drain kafka|singlestore, which waits until what went that way is all in the lake.
brokerage_job() {
  local job="brokerage-$1"
  k delete job "$job" --ignore-not-found >/dev/null
  fill brokerage-bootstrap.yaml | sed -e "s|name: brokerage-bootstrap|name: $job|" \
    -e "s|args: \[\"bootstrap\"\]|args: [\"$1\"${2:+, \"$2\"}]|" | k apply -f - >/dev/null
  finish_job "$job" "the brokerage world's $1${2:+ $2}"
}

# Kafka and kafbat (kafka.yaml), for the generator and the CDC leg; the catalog audits to its log (D23).
kafka_needed() { cdc_running || { is_on gen && is_on kafka; }; }
start_kafka() {
  running kafka && return 0
  say "starting Kafka and kafbat"
  apply kafka.yaml
  k patch deployment kafka-ui --type strategic --patch "$(fill kafka-ui-signin.yaml)" >/dev/null
  k wait --for=condition=available --timeout=8m deployment/kafka deployment/kafka-ui >/dev/null
}
# Kafka's topics stay on its volume (backlog 237), Kafka Connect's offsets among them, so the generator and the sink
# carry on where they stopped. Kafka Connect goes first.
stop_kafka() {
  kafka_needed && return 0
  running kafka || running connect || return 0
  say "stopping Kafka Connect, Kafka and kafbat: neither the generator nor the CDC leg needs them"
  unapply connect.yaml
  unapply kafka.yaml
  k wait --for=delete pod -l 'app in (connect, kafka, kafka-ui)' --timeout=3m >/dev/null 2>&1 || true
}

# The switch gen (D50): the brokerage world's tables and governance, and the generator, which catches up on the
# days it missed (BROKERAGE_CATCH_UP_DAYS), through the ways the switches kafka and ss give it (backlog 238): with
# kafka on, Kafka, Kafka Connect and its sink brokerage-lake for every feed but the orders; with ss on, SingleStore
# for the orders, and with kafka off for every other feed too, each with a change feed. With neither on, kafka goes
# on: the generator needs one way, not SingleStore (Paul, 2026-11-24). While the CDC leg runs, the generator waits
# for cdc stop.
gen_start() {
  if ! is_on ss && ! is_on kafka; then
    set_switch kafka on
  fi
  set_switch gen on
  if is_on ss && ! running singlestore; then
    ss_start
  fi
  if is_on kafka; then
    start_kafka
  fi
  if cdc_running; then
    say "the CDC leg is running: the brokerage world's generator starts with demo/demo.sh cdc stop"
    return 0
  fi
  pause_world
  brokerage_bootstrap
  brokerage_governance
  if is_on ss; then
    world_tables
  fi
  if is_on kafka; then
    world_feeds_stop
    start_connect
  else
    world_feeds_start
  fi
  start_brokerage
}

# The generator stops first, and Kafka Connect and Kafka only after its sink's next commit (every 15 s,
# demo/brokerage/connector.json), so what it sent reaches Iceberg. While the CDC leg runs, Kafka and Kafka Connect
# stay for it, without the world's sink.
gen_stop() {
  set_switch gen off
  if running brokerage; then
    say "stopping the brokerage world's generator, then waiting for its sink's last commit"
    unapply brokerage.yaml
    k wait --for=delete pod -l app=brokerage --timeout=2m >/dev/null 2>&1 || true
    running connect && sleep 20
  fi
  if cdc_running; then
    connect_api DELETE /connectors/brokerage-lake >/dev/null 2>&1 || true
  fi
  world_feeds_stop
  stop_kafka
}

# The catalog's own branch and tag (D33, backlog 110), made through the management API as ana, whose
# demo-analysts hold create on lakehouse, the right refs need. Branch dev is made from main, and the lake writer
# commits the next day's trades and rates on it as user lake, through the warehouse lakehouse@dev (LakeWriter dev);
# Trino's catalog lakehouse_dev (trino.yaml) reads and writes it. Then tag sample keeps main as it is: the sample
# data, and on a new lake nothing of the brokerage world, whose bootstrap comes after. Once per lake: the tag, made
# last, says it is done, and goes with the catalog's PostgreSQL. A dev left without changes by an up that stopped
# part way gets them now; one a person made or merged is left as it is. It returns at its first failure, so the
# tag is never made after a step that failed, and up goes on without the refs (the next up tries again).
refs() {
  local m=/management/v1/catalogs/$CATALOG dev
  api ana GET "$m/refs/sample" '' 200 2>/dev/null && return 0
  say "the catalog's branch dev, with the next day's trades and rates committed on it, and its tag sample (as ana)"
  api ana GET "$m/refs/dev" '' 200 2>/dev/null \
    || api ana POST "$m/refs" '{"name": "dev", "kind": "branch", "from": "main"}' 201 || return 1
  # The branch's own read lists the tables it changed.
  dev=$(tls_curl -fs -H "Authorization: Bearer $(token_of ana)" "$URL$m/refs/dev") || return 1
  if [[ "$dev" == *'"changed":[]'* ]]; then
    lake dev || return 1
  fi
  api ana POST "$m/refs" '{"name": "sample", "kind": "tag", "from": "main"}' 201 || return 1
}

# The brokerage world's bootstrap (brokerage-bootstrap.yaml): its catalog namespaces, tables and views, as user
# brokerage, and its Kafka topics while kafka is on. On every gen, before its governance, sink and generator.
brokerage_bootstrap() {
  say "the brokerage world: its namespaces, tables, views and Kafka topics ($BROKERAGE_IMAGE, bootstrap)"
  brokerage_job bootstrap
}

# The brokerage world's governance (docs/design/brokerage-world.md, the namespace tree), set by stella after the
# bootstrap and before anything writes: every table is classified before its namespace is marked regulated, since a
# regulated namespace refuses writers a table without a classification of its own (D27). Once per lake:
# brokerage.ledger's regulated mark, set last, says it is done, and goes with the catalog's PostgreSQL.
# brokerage.trading is SingleStore's change feed's (feeds.sql), which makes it as it first starts; made here if the
# feed has not yet.
brokerage_governance() {
  local m=/management/v1/catalogs/$CATALOG table
  if curl -fs "${CURL_TLS[@]}" -H "Authorization: Bearer $(token_of stella)" \
      "$URL$m/namespaces/brokerage%1Fledger/governance" 2>/dev/null | grep -q '"regulated"'; then
    return 0
  fi
  say "governance of the brokerage world: classifications, owners, regulated areas"
  classify() {
    api stella PUT "$m/namespaces/$1/attributes/classification" "{\"value\":\"$2\",\"provenance\":\"demo-seed: data catalogue\"}"
  }
  for table in calendar securities; do classify "brokerage%1Freference/tables/$table" public; done
  for table in bars_5m prices_eod; do classify "brokerage%1Fmarkets/tables/$table" internal; done
  classify "brokerage%1Fparty/tables/accounts" restricted
  for table in chart_of_accounts booking_schemes financial_transactions journal_lines posting_rejections balances_eod; do
    classify "brokerage%1Fledger/tables/$table" confidential
  done
  for table in buying_power running_collected_balance; do classify "brokerage%1Fledger/views/$table" confidential; done
  api brokerage GET "/v1/$CATALOG/namespaces/brokerage%1Ftrading" '' 200 2>/dev/null \
    || api brokerage POST "/v1/$CATALOG/namespaces" '{"namespace": ["brokerage", "trading"]}' 200 2>/dev/null \
    || api brokerage GET "/v1/$CATALOG/namespaces/brokerage%1Ftrading" '' 200
  classify "brokerage%1Ftrading" internal
  api stella PUT "$m/namespaces/brokerage/attributes/owner" '{"value":"brokerage-operations","provenance":"demo-seed: HR feed"}'
  api stella PUT "$m/namespaces/brokerage%1Ftrading/attributes/owner" '{"value":"trading-desk","provenance":"demo-seed: HR feed"}'
  api stella PUT "$m/namespaces/brokerage%1Fparty/attributes/regulated" '{"value":"true","provenance":"demo-seed: compliance register"}'
  api stella PUT "$m/namespaces/brokerage%1Fledger/attributes/regulated" '{"value":"true","provenance":"demo-seed: compliance register"}'
}

# Kafka Connect (connect.yaml), applied and waited for until its REST API answers through the front door.
start_connect() {
  say "starting Kafka Connect ($CONNECT_IMAGE)"
  apply connect.yaml
  k rollout status deployment/connect --timeout=5m >/dev/null
  wait_for_connect
}

wait_for_connect() {
  for _ in $(seq 1 30); do
    curl -fs "${CURL_TLS[@]}" "${CONNECT_AUTH[@]}" "$CONNECT_URL/connectors" >/dev/null 2>&1 && return 0
    sleep 2
  done
  echo "Kafka Connect did not answer on $CONNECT_URL; see: kubectl --context $CONTEXT -n $NS logs deployment/connect" >&2
  return 1
}

# The brokerage world's sink, brokerage-lake (demo/brokerage/connector.json: the world's topics into its Iceberg
# tables, through the catalog), put in place on the running worker (the same config twice changes nothing), then its
# generator (brokerage.yaml), last.
start_brokerage() {
  if is_on kafka; then
    say "the brokerage world: its Iceberg sink brokerage-lake, and its generator ($BROKERAGE_IMAGE, route $(world_route))"
    connect_api PUT /connectors/brokerage-lake/config < "$REPO/demo/brokerage/connector.json" >/dev/null
  else
    say "the brokerage world's generator ($BROKERAGE_IMAGE, route $(world_route): Kafka is off)"
  fi
  apply brokerage.yaml
  k rollout status deployment/brokerage --timeout=5m >/dev/null
}

print_access() {
  local expires
  expires=$(tr -d '\r\n' < "$RUN/tokens/expires.txt")
  local web="https://$DEMO_HOST"
  if [[ -f "$RUN/built-commit.txt" ]]; then
    printf '\nThe demo runs commit %s\n' "$(cat "$RUN/built-commit.txt")"
  fi
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

The demo's people sign in once, through Keycloak ($DEMO_HOST:8180), and the console, kafbat, CloudBeaver, Studio
and Grafana ask no password after that: ana, gary, stella and oscar, with the passwords in $PEOPLE_FILE,
made on this machine. Studio asks only the username, then "Sign in with SAML". To change user, use the console's Sign out or
CloudBeaver's Logout (both end Keycloak's session too), or sign out of each UI and of Keycloak
($web:8180/realms/nuna/protocol/openid-connect/logout, then Logout), or use a private window.
To show everything in one sign-in, use presenter, everywhere: every group, every privilege on
every catalog, grants and rules for their own groups too; Silo's and Keycloak's administrator; CloudBeaver's and
Studio's too.

The console:  $URL/ui/   (catalogs lakehouse and engines)
Sign in, or paste one of these tokens (valid until $expires).

Data user  ana   (group demo-analysts: select on both catalogs)
$(token_of ana)

Grant admin  gary  (groups demo-grant-admins: manage grants and read the audit trail, no data access; and
                    demo-auditors: the Audit page, from the audit table nuna_audit.audit.events)
$(token_of gary)

Tag steward stella, platform operator oscar, SingleStore, the lake writer, the brokerage world, the engine matrix,
Kafka Connect and Trino have tokens too: $RUN/tokens/{stella,oscar,singlestore,lake,brokerage,engine-matrix,cdc,trino}.jwt
oscar administers kafbat, Studio and CloudBeaver; in kafbat, ana, gary and stella administer topics, but only
read the catalog's (nuna.*), the Connect worker's, the brokerage world's and those starting with _. oscar reads
kafbat's audit log.
Refs:         lakehouse has branch dev (the next day's trades and rates) and tag sample (the sample data);
              the console's Branches and tags tab merges dev, and Trino's catalog lakehouse_dev reads and writes it
Trino:        CloudBeaver on $web:8185: each person's connections sign in as them, to nuna through
              Trino with their own token, and to the CDC leg's databases
Keycloak:     $web:8180/admin/nuna/console/ for oscar, who reads realm nuna's users, groups, sessions and events
              there without a second sign-in; $web:8180/admin to change it   (user admin, password in $SECRETS/keycloak-admin)
Silo:         its console on $web:8184: "Keycloak" signs ana in to browse and download the warehouse
              bucket's files, read-only, oscar to the server's health, metrics, logs and traces, no data, and
              presenter as Silo's administrator; the S3 store's root, which sees every bucket, signs in with access key nunatest,
              secret key in $SECRETS/silo-root
Kubernetes:   namespace $NS, context $CONTEXT

Switches:     $(switches_line). demo/demo.sh gen, ss, kafka, metrics or cdc turns one on, and demo/demo.sh <switch> stop
              off:
  gen         the brokerage world: its tables and its live generator, through Kafka or SingleStore or both, as
              kafka and ss say (turns kafka on if neither is)
  ss          SingleStore, with its sample databases and change feeds; the generator's orders go to it
  kafka       Kafka and Kafka Connect; every feed of the generator but the orders goes through them
  metrics     Grafana on port 8188, with Prometheus and Tempo: the catalog's and the maintenance worker's metrics
              and traces, on nuna's dashboards
  cdc         PostgreSQL, Oracle and CockroachDB into Iceberg history tables (starts Kafka; stops Trino and
              the generator while it runs)
Engines:      demo/demo.sh engines   (Spark, Kafka Connect, PyIceberg and SingleStore into catalog engines;
              needs gen on)
EOF
  if running brokerage; then
    cat <<EOF
Brokerage:    the brokerage world writes lakehouse.brokerage live, route $(world_route) (kafka, singlestore or both);
              demo/demo.sh brokerage logs follows its generator
EOF
  fi
  if running kafka; then
    cat <<EOF
Kafka:        kafbat on $web:8182; Kafka Connect's connectors there too; its REST API on
              $CONNECT_URL, user operator, password in $SECRETS/connect (curl -K $SECRETS/connect.curlrc)
EOF
  fi
  if running grafana; then
    cat <<EOF
Grafana:      $web:8188: nuna's dashboards (folder nuna) and Explore for Tempo's traces; oscar and presenter
              administer it, everyone else views; admin, password in $SECRETS/grafana-admin, at
              $web:8188/login?disableAutoLogin=true
EOF
  fi
  if running singlestore; then
    cat <<EOF
SingleStore:  Studio on $web:8183, or demo/demo.sh sql   (as root, password in $SECRETS/singlestore-root;
              Studio takes no passwords, so not root)
EOF
  fi
}

# The CDC leg (cdc.yaml), on demand: PostgreSQL backoffice, Oracle and CockroachDB, captured by Debezium into
# Kafka, and written by the Apache Iceberg sink into history tables, one row per change; both run in the demo's
# Kafka Connect (connect.yaml). Its pods do not fit beside Trino on an 8 GB Docker Desktop, so Trino, the audit
# table writer and the brokerage world's generator stop while it runs; CloudBeaver stays, to query the databases.
cdc_running() { k get deployment backoffice >/dev/null 2>&1; }

# The leg's connectors on the worker (demo/cdc/connectors).
CDC_CONNECTORS=(backoffice-source oracle-source cockroach-source backoffice-history oracle-history cockroach-history)

# Kafka Connect's image, built on every up, tagged with the hash of its Dockerfile and plugin poms, so a change to
# either replaces the pod.
build_connect() {
  CONNECT_IMAGE="nuna-demo-connect:$(cat "$STACK/connect.Dockerfile" "$REPO"/demo/cdc/plugins/*/pom.xml | sha256sum | cut -c1-12)"
  docker build -q -f "$STACK/connect.Dockerfile" -t "$CONNECT_IMAGE" "$REPO/demo/cdc/plugins" >/dev/null
}

# A call to Kafka Connect's REST API, with the body of a PUT on stdin; prints the answer, fails the script on
# anything but 2xx. While the front door answers 502, 503 or 504 (Connect stalled, as when Windows pages it out) or
# Connect 409 (its workers rebalancing), it tries again every 2 s for about a minute (backlog 267).
connect_api() {
  local method=$1 path=$2 out in status
  out=$(mktemp)
  in=$(mktemp)
  local args=(-s "${CONNECT_AUTH[@]}" -o "$out" -w '%{http_code}' -X "$method" "$CONNECT_URL$path")
  if [[ "$method" == PUT ]]; then
    # From a file, so a second try sends it again.
    cat > "$in"
    args+=(-H 'Content-Type: application/json' --data-binary "@$in")
  fi
  for try in $(seq 1 30); do
    (( try == 1 )) || sleep 2
    status=$(tls_curl "${args[@]}") || true
    [[ "$status" =~ ^(409|502|503|504)$ ]] || break
  done
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
    "--ssl-revoke-best-effort -K '$SECRETS/connect.curlrc' '$CONNECT_URL/connectors?expand=status'" >&2
  return 1
}

# Runs SQL from stdin in the leg's PostgreSQL, Oracle and CockroachDB.
backoffice_sql() { k exec -i deployment/backoffice -- psql -q -v ON_ERROR_STOP=1 -U backoffice -d backoffice; }
oracle_sql() {
  { echo 'WHENEVER SQLERROR EXIT SQL.SQLCODE'; cat; echo 'EXIT'; } \
    | MSYS_NO_PATHCONV=1 k exec -i deployment/oracle -- sh -c \
      'exec sqlplus -s -l "treasury/\"$TREASURY_PASSWORD\"@//localhost:1521/FREEPDB1"' >/dev/null
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

# The leg's databases and connectors, as cdc.yaml and demo/cdc have them; the connectors' configs are put in place
# on the running worker (the same config twice changes nothing).
cdc_apply() {
  demo_secrets
  # The SQL files with the passwords put in (backlog 282), as Secrets: written to $SECRETS/cdc, which only this
  # user reads, and published from there.
  local sql dir="$SECRETS/cdc" person subst=()
  mkdir -p "$dir"
  for person in "${PEOPLE[@]}"; do
    subst+=(-e "s|\${PASSWORD_$(tr a-z A-Z <<<"$person")}|$(password_of "$person")|g")
  done
  subst+=(-e "s|\${PASSWORD_DEBEZIUM}|$(secret_of debezium)|g" -e "s|\${PASSWORD_TREASURY}|$(secret_of treasury)|g")
  for sql in backoffice oracle cockroach; do sed "${subst[@]}" "$REPO/demo/cdc/$sql.sql" > "$dir/$sql.sql"; done
  k create secret generic backoffice-initdb --from-file=backoffice.sql="$dir/backoffice.sql" \
    --dry-run=client -o yaml | k apply -f - >/dev/null
  k create secret generic oracle-startdb --from-file=cdc.sql="$dir/oracle.sql" --dry-run=client -o yaml \
    | k apply -f - >/dev/null
  k create secret generic cockroach-initdb --from-file=cockroach.sql="$dir/cockroach.sql" \
    --dry-run=client -o yaml | k apply -f - >/dev/null
  # ConfigMaps of the same names from before backlog 282.
  k delete configmap backoffice-initdb oracle-startdb cockroach-initdb --ignore-not-found >/dev/null
  apply cdc.yaml
  apply ports.yaml
  k rollout status deployment/backoffice --timeout=3m >/dev/null
  k rollout status deployment/cockroach --timeout=3m >/dev/null
  # Oracle restarts once into archive log mode (demo/cdc/oracle.sql): about two minutes.
  k rollout status deployment/oracle --timeout=10m >/dev/null
  wait_for_connect
  local connector
  for connector in "${CDC_CONNECTORS[@]}"; do
    connect_api PUT "/connectors/$connector/config" < "$REPO/demo/cdc/connectors/$connector.json" >/dev/null
  done
}

# Deletes the leg's connectors from the worker, each with its offsets, so the next cdc's Debezium snapshots its new
# databases rather than resuming from the last run's positions: a connector's offsets outlive the connector. Kafka
# Connect resets them only for a stopped connector (KIP-875), and stopping one takes a moment.
cdc_connectors_delete() {
  local connector reset
  for connector in "${CDC_CONNECTORS[@]}"; do
    connect_api GET "/connectors/$connector" >/dev/null 2>&1 || continue
    connect_api PUT "/connectors/$connector/stop" </dev/null >/dev/null
    reset=
    for _ in $(seq 1 30); do
      if connect_api DELETE "/connectors/$connector/offsets" >/dev/null 2>&1; then
        reset=yes
        break
      fi
      sleep 2
    done
    [[ -n "$reset" ]] || connect_api DELETE "/connectors/$connector/offsets" >/dev/null
    connect_api DELETE "/connectors/$connector" >/dev/null
  done
}

cdc_start() {
  if tokens_expired; then
    mint
  fi
  publish_tokens
  annotate_tokens
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
  say "stopping Trino, the brokerage world's generator and the maintenance worker: the CDC leg needs their memory"
  k scale deployment/trino --replicas=0 >/dev/null
  k wait --for=delete pod -l app=trino --timeout=2m >/dev/null
  stop_maintenance
  # Beside SingleStore the leg needs the audit table writer's memory too. The catalog sends it the audit records
  # over OTLP (D23, amended 2026-11-23), so while it is stopped the Audit page misses them; the catalog's log keeps
  # them. cdc stop starts it again.
  if is_on ss && running nuna-audit-tables; then
    say "stopping the audit table writer as well, beside SingleStore: the Audit page misses the leg's records"
    k scale deployment/nuna-audit-tables --replicas=0 >/dev/null
    k wait --for=delete pod -l app.kubernetes.io/instance=nuna-audit-tables --timeout=3m >/dev/null
  fi
  # The world pauses; its sink stays, on the worker the leg shares. With Kafka off, the change feeds of its
  # SingleStore tables make their last commits and stop: the leg needs their memory too (cdc stop's gen starts them).
  if k get deployment brokerage >/dev/null 2>&1; then
    k scale deployment/brokerage --replicas=0 >/dev/null
    k wait --for=delete pod -l app=brokerage --timeout=2m >/dev/null 2>&1 || true
    if is_on gen && ! is_on kafka; then
      sleep 20
      world_feeds_stop
    fi
  fi
  # History tables left by an earlier run recorded databases that are gone; each run starts its own.
  local entry ns table
  for entry in "${HISTORY_TABLES[@]}"; do
    read -r ns table <<<"$entry"
    if api ana GET "/v1/$CATALOG/namespaces/$ns/tables/$table" '' 200 2>/dev/null; then
      api cdc DELETE "/v1/$CATALOG/namespaces/$ns/tables/$table?purgeRequested=true" '' 204
    fi
  done
  cockroach_history_table

  start_kafka
  running connect || start_connect
  say "starting the CDC leg: PostgreSQL backoffice, Oracle Free, CockroachDB and their connectors (a few minutes)"
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
Kafka Connect: in kafbat (https://$DEMO_HOST:8182), or curl -s --cacert '$TLS/ca.crt' --ssl-revoke-best-effort
  -K '$SECRETS/connect.curlrc' '$CONNECT_URL/connectors?expand=status' (8186 asks for that password).
kafbat (https://$DEMO_HOST:8182) shows Debezium's change events as they arrive, and the console shows each sink
commit as a snapshot of its history table.
Query the source databases in CloudBeaver (https://$DEMO_HOST:8185): each person's backoffice, treasury and
custody connections sign in as them. Trino and the brokerage world are stopped meanwhile: demo/demo.sh cdc stop
deletes the leg and starts them again. Then read the history in CloudBeaver's Trino connection, oldest change first:
  SELECT "_cdc".op, "_cdc".ts, instruction_id, status FROM lakehouse."history.postgres.settlement".instructions
  ORDER BY "_cdc"."offset";
EOF
}

# Deletes the leg's connectors (with their offsets) and pods, then its Kafka topics and consumer groups, so the
# next cdc starts from nothing; the history tables stay, for Trino to read, and Kafka and Kafka Connect stay only
# while gen is on. The topics go after the connectors, which would otherwise write them again, and from a pod
# of their own: a Kafka tool in the broker's pod could push it past its memory limit.
# The CDC leg's connectors (and their offsets), databases and Kafka topics; Kafka Connect and the history tables stay.
# Its databases keep no volume, so their topics and offsets go with them: a later cdc starts the leg anew.
cdc_delete() {
  say "deleting the CDC leg: its connectors, databases and Kafka topics (Kafka Connect and the history tables stay)"
  cdc_connectors_delete
  k delete deployment,service,configmap -l nuna-demo/cdc=true --ignore-not-found >/dev/null
  k delete secret,configmap backoffice-initdb oracle-startdb cockroach-initdb --ignore-not-found >/dev/null
  k wait --for=delete pod -l 'app in (backoffice, oracle, cockroach)' --timeout=3m >/dev/null 2>&1 || true
  k delete pod cdc-cleanup --ignore-not-found >/dev/null
  MSYS_NO_PATHCONV=1 k run cdc-cleanup -i --rm --quiet --restart=Never --image="$(image_of kafka.image)" \
    --image-pull-policy=Never --env=KAFKA_HEAP_OPTS=-Xmx128m --command -- sh -c '
      bin=/opt/kafka/bin; kafka=--bootstrap-server=kafka:9092
      $bin/kafka-topics.sh $kafka --delete --if-exists --topic "backoffice.*|oracle.*|cockroach.*"
      for group in $($bin/kafka-consumer-groups.sh $kafka --list | grep -E "backoffice|oracle|cockroach"); do
        $bin/kafka-consumer-groups.sh $kafka --delete --group "$group" >/dev/null
      done' >/dev/null
}

cdc_stop() {
  cdc_delete
  say "starting Trino again"
  k scale deployment/trino --replicas=1 >/dev/null
  audit_writer
  maintenance_worker || say "the maintenance worker did not start (see above); the next up tries again (backlog 230)"
  if is_on gen; then
    gen_start
  fi
  stop_kafka
}

up() {
  mkdir -p "$RUN"
  say "building commit $(commit_here)"
  build
  local minted=
  if tokens_expired; then
    mint
    minted=yes
  fi
  kubectl --context "$CONTEXT" create namespace "$NS" --dry-run=client -o yaml | kubectl --context "$CONTEXT" apply -f - >/dev/null
  check_ports
  publish_tokens
  publish_keycloak_keys
  demo_secrets
  publish_tls
  db_tls
  # Kafka Connect and the generator stop for up's memory; gen starts them again last.
  pause_world

  say "starting PostgreSQL, the issuer's key server, Keycloak and Silo (namespace $NS, context $CONTEXT)"
  apply stack.yaml
  apply store-silo.yaml
  k patch deployment s3 --type strategic --patch "$(fill silo-signin.yaml)" >/dev/null
  keycloak_realm
  k wait --for=condition=available --timeout=8m deployment/postgres deployment/idp deployment/keycloak \
    deployment/s3 >/dev/null
  # silo-signin.yaml's patch replaces Silo's pod, and the new one waits for Keycloak: until the rollout ends,
  # the old pod answers alone, and a bucket made in it goes with it.
  k rollout status deployment/s3 --timeout=8m >/dev/null
  # The seed job makes the warehouse bucket if Silo's volume does not hold it yet (a new or wiped one).
  k delete job seed --ignore-not-found >/dev/null
  apply seed.yaml
  k wait --for=condition=complete --timeout=3m job/seed >/dev/null
  # Silo's policy warehouse-read, which Keycloak's client role of that name gives ana and presenter in Silo's
  # console (silo-signin.yaml): Silo keeps it on its volume, and making it again replaces it with the file's.
  k exec -i deployment/s3 -c silo -- sh -c 'cat > /tmp/warehouse-read.json &&
    mc alias set l http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null &&
    mc admin policy create l warehouse-read /tmp/warehouse-read.json >/dev/null' < "$STACK/silo-warehouse-read.json"

  # The switch metrics (backlog 233) as it was, before the catalog, which then sends its metrics and traces to it.
  if is_on metrics; then
    metrics_apply
  fi
  say "installing the catalog (chart deploy/helm/nuna-catalog) and publishing it on $URL"
  catalog_release
  # The server makes its tables only as it starts, so one that outlived its PostgreSQL pod is restarted.
  if [[ -z "$(k exec deployment/postgres -- psql -U catalog -d catalog -tAc "SELECT to_regclass('flyway_schema_history')")" ]]; then
    say "PostgreSQL is new and empty: restarting the catalog so it makes its tables"
    k rollout restart "deployment/$RELEASE" >/dev/null
    k rollout status "deployment/$RELEASE" --timeout=10m >/dev/null
  fi
  # Once the catalog has its tables, so PostgreSQL is past its image's first setup on a new volume.
  db_no_passwords || say "the catalog's PostgreSQL role was not left without a password (see above); the next up tries again (backlog 281)"
  apply ports.yaml
  wait_for_console
  db_check || say "WARNING: the catalog's database does not sign in as backlog 281 sets it (see above); the demo carries on"
  grants
  trusted_engines
  if cdc_running && is_on ss; then
    say "the CDC leg is running beside SingleStore: the audit table writer stays stopped until demo/demo.sh cdc stop"
  else
    audit_writer
  fi
  # Before the brokerage world's bootstrap (gen), so a new lake's tag holds the sample data alone; and, as its lake
  # writer's Job needs, while Kafka Connect and the generator are stopped. A failure in it never stops up.
  seed
  refs || say "the catalog's branch dev and tag sample were not made (see the answer above); the next up tries again (backlog 110)"
  maintenance_policy || say "maintenance was not turned on in $CATALOG's policy (see the answer above); the next up tries again (backlog 230)"
  row_column_rules || say "the row filters and column masks on markets.desks.positions were not set (see the answer above); the next up tries again (backlog 115)"

  # The switches (D50) as they were: SingleStore, then Kafka for the generator or the CDC leg.
  if is_on ss; then
    ss_start "$minted"
  else
    ss_down
  fi
  if kafka_needed; then
    start_kafka
  fi
  if cdc_running; then
    # trino.yaml and brokerage.yaml ask for one replica, which would start Trino and the generator beside the leg;
    # the world's bootstrap Job has no room beside it either.
    say "the CDC leg is running: Trino and the brokerage world stay stopped; bringing Kafka Connect, the leg and CloudBeaver ($CLOUDBEAVER_IMAGE) up to date"
    start_connect
    cdc_apply
  else
    say "starting Trino ($TRINO_IMAGE)"
    apply trino.yaml
    k rollout status deployment/trino --timeout=5m >/dev/null
  fi
  say "starting CloudBeaver ($CLOUDBEAVER_IMAGE), with each person's connections to Trino and the CDC leg's databases"
  apply cloudbeaver.yaml
  k rollout status deployment/cloudbeaver --timeout=5m >/dev/null
  if is_on gen; then
    cdc_running || gen_start
  else
    gen_stop
  fi
  # Last, after the lake writer's Jobs, whose memory it would take, and once the change feeds and the brokerage world's
  # sink have committed: the worker assesses each table once a night, at its first scan in the window, so started
  # earlier it would find nothing to queue until noon. Stopped while the CDC leg runs, as cdc stop says.
  if cdc_running; then
    say "the CDC leg is running: the maintenance worker stays stopped until demo/demo.sh cdc stop"
  else
    maintenance_worker || say "the maintenance worker did not start (see above); the next up tries again (backlog 230)"
  fi
  print_access
}

# Everything stops and goes but the data (backlog 237): the volume claims, PostgreSQL's, Silo's, Kafka's and
# SingleStore's, and configmap demo-seeded, which says the sample data in them is complete. The namespace stays, as
# the claims are in it; demo.sh wipe deletes it. The CDC leg goes as cdc stop deletes it: its databases keep no
# volume, so the offsets Kafka keeps for them must go too. The compaction Jobs' namespace holds nothing to keep.
down() {
  local release
  kubectl --context "$CONTEXT" get namespace "$NS" >/dev/null 2>&1 || return 0
  if cdc_running; then
    cdc_delete || say "the CDC leg was not cleanly deleted (see above): its Kafka topics or offsets may be left"
  fi
  for release in $(helm --kube-context "$CONTEXT" -n "$NS" list -q 2>/dev/null); do
    helm --kube-context "$CONTEXT" -n "$NS" uninstall "$release" --wait >/dev/null 2>&1 || true
  done
  say "deleting namespace $NS's pods and settings; its volumes stay (demo/demo.sh wipe deletes them)"
  k delete deployment,statefulset,job,service,secret,poddisruptionbudget,role,rolebinding,networkpolicy,ingress \
    --all --ignore-not-found --wait=true >/dev/null
  k delete configmap --field-selector 'metadata.name!=demo-seeded,metadata.name!=kube-root-ca.crt' \
    --ignore-not-found >/dev/null
  k delete serviceaccount --field-selector metadata.name!=default --ignore-not-found >/dev/null
  k delete pod --all --ignore-not-found --wait=true >/dev/null
  kubectl --context "$CONTEXT" delete namespace "$NS-compaction" --ignore-not-found --wait=true >/dev/null
}

# The memory the CDC leg holds is what Spark's Job and the lake writer's need.
refuse_while_cdc() {
  if cdc_running; then
    echo "the CDC leg is running, and $1 does not fit beside it: demo/demo.sh cdc stop first" >&2
    exit 1
  fi
}

# The engine matrix's tests against this stack: its engines run as Jobs here, into catalog engines, and the
# stack stays as it is. Results are in catalog-tests-engines/build/engines-run, of $RUN/checkout when started in the
# main checkout. Trino, CloudBeaver, Kafka Connect
# and the brokerage world's generator stop for the run: Spark's Job asks for 2 GiB, which an 8 GB Docker Desktop
# has only without them.
engines() {
  if tokens_expired; then
    mint
    publish_tokens
    annotate_tokens
  fi
  say "stopping Trino, CloudBeaver, Kafka Connect, the brokerage world and the maintenance worker for the run"
  k scale deployment/trino deployment/cloudbeaver --replicas=0 >/dev/null
  k wait --for=delete pod -l 'app in (trino, cloudbeaver)' --timeout=2m >/dev/null
  pause_world
  stop_maintenance
  say "running the engine matrix against namespace $NS"
  local status=0
  (cd "$REPO" && ./gradlew :catalog-tests-engines:test --rerun -Pnightly --tests EngineMatrixTest \
    -Pengines.cluster="$CONTEXT" -Pengines.stack="$NS" -Pengines.token-file="$RUN/tokens/engine-matrix.jwt" -Ps3=silo) \
    || status=$?
  say "starting Trino, CloudBeaver, Kafka Connect, the brokerage world and the maintenance worker again"
  k scale deployment/trino deployment/cloudbeaver --replicas=1 >/dev/null
  resume_world
  if running nuna-maintenance; then
    k scale deployment/nuna-maintenance --replicas=1 >/dev/null
  fi
  return "$status"
}

case "${1:-}" in
  up)
    up
    ;;
  engines)
    refuse_while_cdc "the engine matrix"
    if ! running kafka || ! running singlestore; then
      echo "the engine matrix needs Kafka and SingleStore: demo/demo.sh gen first, with kafka and ss on" >&2
      exit 1
    fi
    engines
    ;;
  gen)
    load_images
    case "${2:-}" in
      "") gen_start; print_access ;;
      stop) gen_stop ;;
      *) sed -n '2,41p' "$0"; exit 2 ;;
    esac
    ;;
  ss)
    load_images
    case "${2:-}" in
      "") ss_on; print_access ;;
      stop) ss_stop ;;
      *) sed -n '2,41p' "$0"; exit 2 ;;
    esac
    ;;
  kafka)
    load_images
    case "${2:-}" in
      "") kafka_on ;;
      stop) kafka_off ;;
      *) sed -n '2,41p' "$0"; exit 2 ;;
    esac
    ;;
  metrics)
    case "${2:-}" in
      "") metrics_start; print_access ;;
      stop) metrics_stop ;;
      *) sed -n '2,41p' "$0"; exit 2 ;;
    esac
    ;;
  token)
    if tokens_expired; then
      mint
      publish_tokens
      say "re-minted; replacing SingleStore's running pipelines and restarting Trino, CloudBeaver, Kafka Connect and the brokerage world's generator so they use the new tokens"
      if running singlestore; then
        feeds
      fi
      annotate_tokens
    fi
    print_access
    ;;
  sql)
    if ! running singlestore; then
      echo "SingleStore is off: demo/demo.sh ss first" >&2
      exit 1
    fi
    MSYS_NO_PATHCONV=1 k exec -it deployment/singlestore -- sh -c 'exec singlestore -uroot -p"$ROOT_PASSWORD" ops'
    ;;
  rate)
    refuse_while_cdc "the lake writer"
    # The lake writer's Job needs Kafka Connect's and the generator's memory meanwhile.
    pause_world
    status=0
    lake rate || status=$?
    resume_world
    exit "$status"
    ;;
  brokerage)
    case "${2:-}" in
      logs) k logs deployment/brokerage --tail=100 -f ;;
      *) sed -n '2,41p' "$0"; exit 2 ;;
    esac
    ;;
  cdc)
    load_images
    case "${2:-}" in
      "") cdc_start ;;
      stop) cdc_stop ;;
      *) sed -n '2,41p' "$0"; exit 2 ;;
    esac
    ;;
  psql)
    # Inside the pod psql connects by socket, which the image trusts: no password asked.
    k exec -it deployment/backoffice -- psql -U "${2:-backoffice}" -d backoffice
    ;;
  sqlplus)
    # treasury signs in with the password in the pod's environment; a person with their Keycloak password
    # (demo/cdc/oracle.sql), which SQL*Plus asks for: it is in $PEOPLE_FILE.
    if [[ -n "${2:-}" ]]; then
      echo "$2's password is in $PEOPLE_FILE"
      MSYS_NO_PATHCONV=1 k exec -it deployment/oracle -- sqlplus -l "$2@//localhost:1521/FREEPDB1"
    else
      MSYS_NO_PATHCONV=1 k exec -it deployment/oracle -- sh -c \
        'exec sqlplus -l "treasury/\"$TREASURY_PASSWORD\"@//localhost:1521/FREEPDB1"'
    fi
    ;;
  cockroach)
    # Root signs in with the client certificate the image made; a person with their Keycloak password
    # (demo/cdc/cockroach.sql), which the prompt asks for: it is in $PEOPLE_FILE.
    if [[ -n "${2:-}" ]]; then
      echo "$2's password is in $PEOPLE_FILE"
      MSYS_NO_PATHCONV=1 k exec -it deployment/cockroach -- ./cockroach sql \
        --url "postgresql://$2@localhost:26257/custody?sslmode=disable"
    else
      MSYS_NO_PATHCONV=1 k exec -it deployment/cockroach -- ./cockroach sql --certs-dir=certs --host=127.0.0.1 -d custody
    fi
    ;;
  status)
    # The deployments too: a stopped one (the brokerage world's generator while the CDC leg runs) has no pod.
    k get deployments,pods,services
    echo
    echo "Switches: $(switches_line)"
    echo
    echo "Kafka Connect's connectors (the connector's state, then its tasks'):"
    if names=$(curl -fs "${CURL_TLS[@]}" "${CONNECT_AUTH[@]}" "$CONNECT_URL/connectors" 2>/dev/null); then
      for name in $(grep -o '"[^"]*"' <<<"$names" | tr -d '"'); do
        printf '  %-22s %s\n' "$name" "$(curl -fs "${CURL_TLS[@]}" "${CONNECT_AUTH[@]}" "$CONNECT_URL/connectors/$name/status" \
          | grep -o '"state":"[A-Z_]*"' | cut -d'"' -f4 | tr '\n' ' ')"
      done
    elif running connect; then
      echo "  Kafka Connect does not answer on $CONNECT_URL"
    else
      echo "  Kafka Connect is off (it runs while gen or cdc is on)"
    fi
    ;;
  down)
    down
    ;;
  wipe)
    down
    say "deleting namespace $NS with its volumes: PostgreSQL's, Silo's, Kafka's and SingleStore's data"
    kubectl --context "$CONTEXT" delete namespace "$NS" --ignore-not-found --wait=true
    ;;
  *)
    sed -n '2,41p' "$0"
    exit 2
    ;;
esac
