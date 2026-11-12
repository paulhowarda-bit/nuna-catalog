#!/usr/bin/env bash
# The local demo of fidice-catalog, on Docker Desktop's Kubernetes: the stack it shares with the engine matrix
# (demo/k8s), its sample data, and the matrix's engines on demand. See demo/README.md.
#
#   demo/demo.sh up       build the jars and images, start the stack, load sample data once, print tokens
#   demo/demo.sh engines  run the engine matrix (Spark, Kafka Connect, PyIceberg, SingleStore) against the stack
#   demo/demo.sh token    print the URLs and the tokens again (re-mints them if they have expired)
#   demo/demo.sh sql      open a SingleStore prompt (databases ops and lake)
#   demo/demo.sh rate     append an FX rate to Iceberg (markets.fx.rates); SingleStore loads it into lake.fx_rates
#   demo/demo.sh status   show the pods and services
#   demo/demo.sh down     delete the namespace and everything in it (keeps the signing key and tokens)
#
# Needs bash (Git Bash on Windows), Docker Desktop with Kubernetes on, kubectl, helm, curl, unzip, and JDK 25
# (JAVA_HOME or java on PATH). kubectl and helm always use FIDICE_DEMO_CONTEXT (default docker-desktop), never
# the current context, and the stack is namespace FIDICE_DEMO_NAMESPACE (default fidice-demo). Docker
# Desktop's cluster runs images from the local Docker engine and serves LoadBalancer Services on localhost.
# The signing key and tokens are kept in FIDICE_DEMO_HOME (default ~/.fidice-demo).
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN="${FIDICE_DEMO_HOME:-$HOME/.fidice-demo}"
CONTEXT="${FIDICE_DEMO_CONTEXT:-docker-desktop}"
NS="${FIDICE_DEMO_NAMESPACE:-fidice-demo}"
STACK="$REPO/demo/k8s"
RELEASE=fidice-catalog
URL=http://localhost:8181
CATALOG=lakehouse
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
# SingleStore's root password, as demo/k8s/singlestore.yaml sets it. Local only.
S2_ROOT_PASSWORD=fidice-test
KAFKA_UI_IMAGE=kafbat/kafka-ui:v1.5.0
SINGLESTORE_IMAGE=

k() { kubectl --context "$CONTEXT" -n "$NS" "$@"; }

say() { printf '\n== %s\n' "$*"; }

token_of() { tr -d '\r\n' < "$RUN/tokens/$1.jwt"; }

# An image named in gradle.properties, the versions the builds use.
image_of() { sed -n "s/^$1=//p" "$REPO/gradle.properties" | tr -d '\r'; }

# A manifest from demo/k8s with the run's ${...} values put in, applied.
apply() {
  sed -e "s|\${SILO_IMAGE}|$(image_of silo.image)|g" -e "s|\${S3_ENDPOINT}|http://s3:9000|g" \
    -e "s|\${KAFKA_UI_IMAGE}|$KAFKA_UI_IMAGE|g" -e "s|\${SINGLESTORE_IMAGE}|$SINGLESTORE_IMAGE|g" \
    "$STACK/$1" | k apply -f - >/dev/null
}

# An API call as a demo user; fails the script on anything but the expected status.
api() {
  local user=$1 method=$2 path=$3 body=${4:-} expect=${5:-2}
  local out status
  out=$(mktemp)
  local args=(-s -o "$out" -w '%{http_code}' -X "$method" "$URL$path" -H "Authorization: Bearer $(token_of "$user")")
  if [[ -n "$body" ]]; then
    args+=(-H 'Content-Type: application/json' --data "$body")
  fi
  status=$(curl "${args[@]}")
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
# Docker do nothing when nothing changed. SingleStore's image is tagged with the connector jar's hash, so a
# changed connector replaces the pod (and up seeds the new one). The cluster never pulls: images it runs
# come from the local Docker engine.
build() {
  say "building the catalog server and change-feed connector jars (no tests run)"
  (cd "$REPO" && ./gradlew -q :catalog-server:bootJar :singlestore-change-feed:connectorJar)
  local jar="$REPO/singlestore-change-feed/build/libs/fidice-changefeed-connector.jar"
  rm -rf "$RUN/images"
  mkdir -p "$RUN/images/singlestore" "$RUN/images/lake/lib"
  cp "$jar" "$RUN/images/singlestore/"
  unzip -q -j "$jar" 'lib/*' -d "$RUN/images/lake/lib"
  cp "$REPO/demo/LakeWriter.java" "$RUN/images/lake/"

  say "building the images: the catalog, SingleStore with the connector, the lake writer"
  docker build -q -f "$REPO/deploy/image/Dockerfile" -t fidice-catalog:demo "$REPO/catalog-server/build/libs" >/dev/null
  SINGLESTORE_IMAGE="fidice-demo-singlestore:$(sha256sum "$jar" | cut -c1-12)"
  docker build -q -f "$STACK/singlestore.Dockerfile" -t "$SINGLESTORE_IMAGE" "$RUN/images/singlestore" >/dev/null
  docker build -q -f "$STACK/lake.Dockerfile" -t fidice-demo-lake:local "$RUN/images/lake" >/dev/null

  local image
  for image in "$(image_of postgres.image)" "$(image_of kafka.image)" "$(image_of silo.image)" busybox:1.37.0 \
      "$KAFKA_UI_IMAGE"; do
    docker image inspect "$image" >/dev/null 2>&1 || docker pull -q "$image" >/dev/null
  done
}

mint() {
  "$JAVA" "$REPO/demo/Mint.java" "$RUN"
}

# The issuer's public keys (served by Service idp) and the lake writer's token.
publish_tokens() {
  k create configmap idp-keys --from-file=keys.json="$RUN/idp/keys.json" --dry-run=client -o yaml | k apply -f - >/dev/null
  k create secret generic demo-lake-token --from-literal=token="$(token_of lake)" --dry-run=client -o yaml | k apply -f - >/dev/null
}

tokens_expired() {
  [[ ! -f "$RUN/tokens/expires.txt" ]] && return 0
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
  for port in 8181 8182 8183; do
    if (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then busy+=("$port"); fi
  done
  if (( ${#busy[@]} )); then
    echo "localhost port(s) ${busy[*]} are in use; the demo publishes 8181, 8182 and 8183. Stop what holds them." >&2
    return 1
  fi
}

wait_for_console() {
  for _ in $(seq 1 120); do
    curl -fs "$URL/ui/config.json" >/dev/null 2>&1 && return 0
    sleep 2
  done
  echo "the catalog did not answer on $URL; see: kubectl --context $CONTEXT -n $NS logs deployment/$RELEASE" >&2
  return 1
}

grants() {
  say "granting the demo groups and the engine matrix their privileges (by SQL)"
  k exec -i deployment/postgres -- psql -q -v ON_ERROR_STOP=1 -U catalog -d catalog <<'SQL'
INSERT INTO grant_record (catalog_id, securable_kind, securable_id, group_name, privilege, granted_by, granted_at)
SELECT c.id, 'CATALOG', c.id, g.group_name, g.privilege, 'demo-setup', now()
FROM catalog c,
     (VALUES ('lakehouse', 'demo-loaders', 'create'), ('lakehouse', 'demo-loaders', 'modify'),
             ('lakehouse', 'demo-analysts', 'select'),
             ('lakehouse', 'demo-stewards', 'manage-tags'),
             ('engines', 'engine-clients', 'create'), ('engines', 'engine-clients', 'modify'),
             ('engines', 'demo-analysts', 'select')) AS g(catalog_name, group_name, privilege)
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
# offsets in Iceberg that it cannot continue from, so its first commit is a resnapshot.
seed() {
  local seeded=
  k get configmap demo-seeded >/dev/null 2>&1 && seeded=yes
  if [[ -z "$seeded" ]]; then
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
  local m=/management/v1/catalogs/$CATALOG ns='markets%1Fequities%1Feu%1Fxetra'
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
  cat <<EOF

The console:  $URL/ui/   (catalogs lakehouse and engines)
Sign in by pasting one of these tokens (valid until $expires).

Data user  ana   (group demo-analysts: select on both catalogs)
$(token_of ana)

Grant admin  gary  (group demo-grant-admins: manage grants and read the audit trail; no data access)
$(token_of gary)

Tag steward stella, platform operator oscar, SingleStore, the lake writer and the engine matrix have tokens too:
  $RUN/tokens/{stella,oscar,singlestore,lake,engine-matrix}.jwt
SingleStore:  demo/demo.sh sql   (or Studio on http://localhost:8183, user root, password $S2_ROOT_PASSWORD)
Kafka:        kafbat on http://localhost:8182
Engines:      demo/demo.sh engines   (Spark, Kafka Connect, PyIceberg and SingleStore into catalog engines)
Kubernetes:   namespace $NS, context $CONTEXT
EOF
}

up() {
  mkdir -p "$RUN"
  build
  mint
  kubectl --context "$CONTEXT" create namespace "$NS" --dry-run=client -o yaml | kubectl --context "$CONTEXT" apply -f - >/dev/null
  check_ports
  publish_tokens

  say "starting PostgreSQL, Kafka, kafbat, the issuer's key server and Silo (namespace $NS, context $CONTEXT)"
  apply stack.yaml
  apply store-silo.yaml
  k wait --for=condition=available --timeout=8m deployment/postgres deployment/kafka deployment/idp \
    deployment/kafka-ui deployment/s3 >/dev/null
  # The seed job makes the warehouse bucket; a completed job is left as it is.
  apply seed.yaml
  k wait --for=condition=complete --timeout=3m job/seed >/dev/null

  say "installing the catalog (chart deploy/helm/fidice-catalog) and publishing it on $URL"
  # The image's id as a pod annotation: a rebuilt image under the same tag rolls the server, an unchanged
  # one does not.
  helm --kube-context "$CONTEXT" -n "$NS" upgrade --install "$RELEASE" "$REPO/deploy/helm/fidice-catalog" \
    -f "$STACK/catalog-values.yaml" -f "$STACK/demo-values.yaml" \
    --set-string "podAnnotations.fidice-demo/image=$(image_id fidice-catalog:demo)" --wait --timeout 10m >/dev/null
  apply ports.yaml
  wait_for_console
  grants

  say "starting SingleStore ($SINGLESTORE_IMAGE)"
  apply singlestore.yaml
  k rollout status deployment/singlestore --timeout=15m >/dev/null
  seed
  print_access
}

# The engine matrix's tests against this stack: its engines run as Jobs here, into catalog engines, and the
# stack stays as it is. Results are in catalog-tests-engines/build/engines-run.
engines() {
  if tokens_expired; then
    mint
    publish_tokens
  fi
  say "running the engine matrix against namespace $NS"
  (cd "$REPO" && ./gradlew :catalog-tests-engines:test --rerun -Pnightly --tests EngineMatrixTest \
    -Pengines.cluster="$CONTEXT" -Pengines.stack="$NS" -Pengines.token-file="$RUN/tokens/engine-matrix.jwt" -Ps3=silo)
}

case "${1:-}" in
  up)
    up
    ;;
  engines)
    engines
    ;;
  token)
    if tokens_expired; then
      mint
      publish_tokens
      say "re-minted; replacing SingleStore's running pipelines so they use the new token"
      feeds
    fi
    print_access
    ;;
  sql)
    MSYS_NO_PATHCONV=1 k exec -it deployment/singlestore -- singlestore -uroot -p"$S2_ROOT_PASSWORD" ops
    ;;
  rate)
    lake rate
    ;;
  status)
    k get pods,services
    ;;
  down)
    helm --kube-context "$CONTEXT" -n "$NS" uninstall "$RELEASE" --wait 2>/dev/null || true
    kubectl --context "$CONTEXT" delete namespace "$NS" --ignore-not-found --wait=true
    ;;
  *)
    sed -n '2,17p' "$0"
    exit 2
    ;;
esac
