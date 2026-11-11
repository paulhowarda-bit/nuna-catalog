#!/usr/bin/env bash
# The local demo of fidice-catalog and its web console. See demo/README.md.
#
#   demo/demo.sh up      build the server jar if needed, start the stack, load sample data once, print tokens
#   demo/demo.sh token   print the console URL and the tokens again (re-mints them if they have expired)
#   demo/demo.sh trino   open a Trino prompt on the catalog (catalog "fid")
#   demo/demo.sh status  show the containers
#   demo/demo.sh down    stop and remove the stack and its data (keeps the signing key and tokens)
#
# Needs bash (Git Bash on Windows), Docker with compose, curl, and JDK 25 (JAVA_HOME or java on PATH).
# The stack runs from a copy in FIDICE_DEMO_HOME (default ~/.fidice-demo), so it keeps running when the
# checkout it came from changes or goes away.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN="${FIDICE_DEMO_HOME:-$HOME/.fidice-demo}"
PROJECT=fidice-demo
URL=http://localhost:8181
CATALOG=lakehouse
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"

compose() {
  FIDICE_TOKEN="$(cat "$RUN/tokens/trino.jwt" 2>/dev/null || echo unset)" docker compose -p "$PROJECT" -f "$RUN/compose.yaml" "$@"
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

trino_sql() {
  MSYS_NO_PATHCONV=1 docker exec "$PROJECT-trino-1" trino --catalog fid --output-format TSV --execute "$1"
}

build_jar() {
  local jar
  jar=$(ls "$REPO"/catalog-server/target/catalog-server-*-exec.jar 2>/dev/null | head -1 || true)
  if [[ -z "$jar" || "${FIDICE_DEMO_BUILD:-}" == 1 ]]; then
    say "building the catalog server jar (tests skipped)"
    (cd "$REPO" && ./mvnw -q -DskipTests package -pl catalog-server -am)
    jar=$(ls "$REPO"/catalog-server/target/catalog-server-*-exec.jar | head -1)
  fi
  cp "$jar" "$RUN/catalog.jar"
}

prepare() {
  mkdir -p "$RUN/trino/catalog"
  cp "$REPO/demo/compose.yaml" "$REPO/demo/demo.properties" "$RUN/"
  cp "$REPO/demo/trino/seed.sql" "$RUN/trino/"
  cp "$REPO/demo/trino/catalog/fid.properties" "$RUN/trino/catalog/"
  build_jar
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

seed() {
  if [[ -f "$RUN/seeded" ]]; then
    echo "sample data already loaded"
    return
  fi
  say "loading sample data through Trino"
  MSYS_NO_PATHCONV=1 docker exec "$PROJECT-trino-1" trino --catalog fid --file /scripts/seed.sql >/dev/null

  say "tagging and branching the trades table through the REST API"
  local snapshots first current ns
  snapshots=$(trino_sql "SELECT snapshot_id FROM \"markets.equities.eu.xetra\".\"trades\$snapshots\" ORDER BY committed_at")
  first=$(echo "$snapshots" | tr -d '\r' | head -1)
  current=$(echo "$snapshots" | tr -d '\r' | tail -1)
  ns='markets%1Fequities%1Feu%1Fxetra'
  api trino POST "/v1/$CATALOG/namespaces/$ns/tables/trades" \
    "{\"requirements\":[],\"updates\":[{\"action\":\"set-snapshot-ref\",\"ref-name\":\"eod-2026-11-09\",\"type\":\"tag\",\"snapshot-id\":$first}]}"
  api trino POST "/v1/$CATALOG/namespaces/$ns/tables/trades" \
    "{\"requirements\":[],\"updates\":[{\"action\":\"set-snapshot-ref\",\"ref-name\":\"surveillance-review\",\"type\":\"branch\",\"snapshot-id\":$current}]}"

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

  say "a few reads, so the audit trail has some"
  trino_sql "SELECT count(*) FROM \"markets.equities.eu.xetra\".trades" >/dev/null
  trino_sql "SELECT * FROM \"markets.fx\".rates" >/dev/null
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

Tag steward stella, platform operator oscar and the Trino loader have tokens too:
  $RUN/tokens/{stella,oscar,trino}.jwt
Trino:  demo/demo.sh trino   (or http://localhost:8183, user any name)
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
    say "starting Trino"
    compose up -d --wait trino
    seed
    print_access
    ;;
  token)
    if tokens_expired; then
      mint
      say "re-minted; restarting Trino so it uses its new token"
      compose up -d --force-recreate --wait trino
    fi
    print_access
    ;;
  trino)
    MSYS_NO_PATHCONV=1 docker exec -it "$PROJECT-trino-1" trino --catalog fid
    ;;
  status)
    compose ps
    ;;
  down)
    compose down -v --remove-orphans
    rm -f "$RUN/seeded"
    ;;
  *)
    sed -n '2,12p' "$0"
    exit 2
    ;;
esac
