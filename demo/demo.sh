#!/usr/bin/env bash
# The local demo of fidice-catalog and its web console. See demo/README.md.
#
#   demo/demo.sh up      build the jars, start the stack, load sample data once, print tokens
#   demo/demo.sh token   print the console URL and the tokens again (re-mints them if they have expired)
#   demo/demo.sh sql     open a SingleStore prompt (databases ops and lake)
#   demo/demo.sh rate    append an FX rate to Iceberg (markets.fx.rates); SingleStore loads it into lake.fx_rates
#   demo/demo.sh status  show the containers
#   demo/demo.sh down    stop and remove the stack and its data (keeps the signing key and tokens)
#
# Needs bash (Git Bash on Windows), Docker with compose, curl, unzip, and JDK 25 (JAVA_HOME or java on
# PATH). The stack runs from a copy in FIDICE_DEMO_HOME (default ~/.fidice-demo), so it keeps running when
# the checkout it came from changes or goes away. FIDICE_DEMO_PROJECT names the compose project (default
# fidice-demo), to run a second copy beside it.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN="${FIDICE_DEMO_HOME:-$HOME/.fidice-demo}"
PROJECT="${FIDICE_DEMO_PROJECT:-fidice-demo}"
URL=http://localhost:8181
CATALOG=lakehouse
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
# SingleStore's root password, as compose.yaml sets it. Local only.
S2_ROOT_PASSWORD=fidicedemo-root

# FIDICE_TOKEN is the lake writer's token: the lake service is the only one that reads it.
compose() {
  FIDICE_TOKEN="$(cat "$RUN/tokens/lake.jwt" 2>/dev/null || echo unset)" docker compose -p "$PROJECT" -f "$RUN/compose.yaml" "$@"
}

say() { printf '\n== %s\n' "$*"; }

token_of() { tr -d '\r\n' < "$RUN/tokens/$1.jwt"; }

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
  MSYS_NO_PATHCONV=1 docker exec -i "$PROJECT-singlestore-1" singlestore -uroot -p"$S2_ROOT_PASSWORD" -N -B \
    2> >(grep -v -e 'getopt: command not found' -e 'Using a password on the command line' >&2)
}

# A SQL file from demo/singlestore, with the singlestore user's catalog token and the root password put in.
s2_file() {
  sed -e "s|\${SINGLESTORE_TOKEN}|$(token_of singlestore)|g" -e "s|\${SINGLESTORE_ROOT_PASSWORD}|$S2_ROOT_PASSWORD|g" \
    "$RUN/singlestore/$1" | s2
}

# Built from this checkout on every up, so the stack never runs a jar older than the code; Gradle
# does nothing when the jars are already current. The connector jar is mounted into SingleStore; its
# Iceberg libraries also run the lake writer.
build_jars() {
  say "building the catalog server and change-feed connector jars (no tests run)"
  (cd "$REPO" && ./gradlew -q :catalog-server:bootJar :singlestore-change-feed:connectorJar)
  cp "$(ls -t "$REPO"/catalog-server/build/libs/catalog-server-*-exec.jar | head -1)" "$RUN/catalog.jar"
  cp "$REPO/singlestore-change-feed/build/libs/fidice-changefeed-connector.jar" "$RUN/"
  rm -rf "$RUN/lake/lib"
  unzip -q -j "$RUN/fidice-changefeed-connector.jar" 'lib/*' -d "$RUN/lake/lib"
}

prepare() {
  mkdir -p "$RUN/singlestore" "$RUN/lake"
  cp "$REPO/demo/compose.yaml" "$REPO/demo/demo.properties" "$RUN/"
  cp "$REPO"/demo/singlestore/*.sql "$RUN/singlestore/"
  cp "$REPO/demo/LakeWriter.java" "$RUN/lake/"
  build_jars
  mint
}

mint() {
  "$JAVA" "$REPO/demo/Mint.java" "$RUN"
}

tokens_expired() {
  [[ ! -f "$RUN/tokens/expires.txt" ]] && return 0
  local expires now
  expires=$(date -d "$(tr -d '\r\n' < "$RUN/tokens/expires.txt")" +%s)
  now=$(date +%s)
  (( expires - now < 3600 ))
}

wait_for_console() {
  for _ in $(seq 1 120); do
    curl -fs "$URL/ui/config.json" >/dev/null 2>&1 && return 0
    sleep 2
  done
  echo "the catalog did not come up on $URL; see: docker logs $PROJECT-catalog-1" >&2
  return 1
}

grants() {
  say "granting the demo groups their privileges (by SQL, as the engine matrix does)"
  MSYS_NO_PATHCONV=1 docker exec -i "$PROJECT-postgres-1" psql -q -v ON_ERROR_STOP=1 -U catalog -d catalog <<'SQL'
INSERT INTO grant_record (catalog_id, securable_kind, securable_id, group_name, privilege, granted_by, granted_at)
SELECT c.id, 'CATALOG', c.id, g.group_name, g.privilege, 'demo-setup', now()
FROM catalog c,
     (VALUES ('demo-loaders', 'create'), ('demo-loaders', 'modify'),
             ('demo-analysts', 'select'),
             ('demo-stewards', 'manage-tags')) AS g(group_name, privilege)
WHERE c.name_norm = 'lakehouse'
ON CONFLICT DO NOTHING;
SQL
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

# Waits until the change feed has made a commit of the given kind; fails after two minutes.
wait_for_commit() {
  local kind=$1
  for _ in $(seq 1 60); do
    [[ "$(echo "SELECT COUNT(*) FROM ops.lake_commits WHERE kind = '$kind';" | s2)" != 0* ]] && return 0
    sleep 2
  done
  echo "the change feed made no '$kind' commit. In demo/demo.sh sql:" >&2
  echo "  SELECT ERROR_MESSAGE FROM information_schema.PIPELINES_ERRORS ORDER BY ERROR_UNIX_TIMESTAMP DESC LIMIT 3;" >&2
  return 1
}

seed() {
  if [[ -f "$RUN/seeded" ]]; then
    echo "sample data already loaded"
    return
  fi
  say "writing the sample Iceberg tables through the catalog (demo/LakeWriter.java)"
  compose run --rm lake seed

  say "SingleStore: the ops and lake databases, and one-time loads from Iceberg"
  s2_file seed.sql >/dev/null
  s2_file load.sql >/dev/null

  say "SingleStore: the change feed of ops.orders into Iceberg, and the FX rates feed from Iceberg"
  feeds
  wait_for_commit snapshot
  s2 >/dev/null <<'SQL'
UPDATE ops.orders SET status = 'FILLED', updated_at = NOW(6) WHERE order_id = 1001;
UPDATE ops.orders SET qty = 60, status = 'PARTIAL', updated_at = NOW(6) WHERE order_id = 1002;
DELETE FROM ops.orders WHERE order_id = 1003;
INSERT INTO ops.orders VALUES (1005, 'SAP', 'SELL', 100, 182.500000, 'NEW', NOW(6));
SQL
  wait_for_commit changes

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
  touch "$RUN/seeded"
}

print_access() {
  local expires
  expires=$(tr -d '\r\n' < "$RUN/tokens/expires.txt")
  cat <<EOF

The console:  $URL/ui/
Sign in by pasting one of these tokens (valid until $expires).

Data user  ana   (group demo-analysts: select on the whole catalog)
$(token_of ana)

Grant admin  gary  (group demo-grant-admins: manage grants and read the audit trail; no data access)
$(token_of gary)

Tag steward stella, platform operator oscar, SingleStore and the lake writer have tokens too:
  $RUN/tokens/{stella,oscar,singlestore,lake}.jwt
SingleStore:  demo/demo.sh sql   (or Studio on http://localhost:8183, user root, password $S2_ROOT_PASSWORD)
EOF
}

case "${1:-}" in
  up)
    mkdir -p "$RUN"
    prepare
    say "starting PostgreSQL, Kafka, Silo and the catalog (project $PROJECT)"
    compose up -d --wait postgres kafka silo
    compose up -d seed catalog
    wait_for_console
    grants
    say "starting SingleStore"
    compose up -d --wait singlestore
    seed
    print_access
    ;;
  token)
    if tokens_expired; then
      mint
      say "re-minted; replacing SingleStore's running pipelines so they use the new token"
      feeds
    fi
    print_access
    ;;
  sql)
    MSYS_NO_PATHCONV=1 docker exec -it "$PROJECT-singlestore-1" singlestore -uroot -p"$S2_ROOT_PASSWORD" ops
    ;;
  rate)
    compose run --rm lake rate
    ;;
  status)
    compose ps
    ;;
  down)
    compose down -v --remove-orphans
    rm -f "$RUN/seeded"
    ;;
  *)
    sed -n '2,14p' "$0"
    exit 2
    ;;
esac
