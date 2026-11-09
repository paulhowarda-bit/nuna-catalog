#!/usr/bin/env bash
# Flink SQL against the catalog: a local session cluster in this container, then the SQL client. Flink SQL
# shows neither a table's format version nor whether a namespace exists, so between the two scripts this
# asks the catalog's REST API directly (same token) and prints those results.
set -euo pipefail
for file in catalogs.sql tables.sql drop.sql; do
  sed "s|\${FIDICE_TOKEN}|${FIDICE_TOKEN}|g" "/scripts/$file" > "/tmp/$file"
done

sql() { # the SQL client stops at a failing statement but still exits 0, so its own [ERROR] decides
  /opt/flink/bin/sql-client.sh -i /tmp/catalogs.sql -f "/tmp/$1" 2>&1 | tee "/tmp/$1.out"
  if grep -q '\[ERROR\]' "/tmp/$1.out"; then echo "Flink SQL failed in $1" >&2; exit 1; fi
}

catalog() { # prints the HTTP status of GET /v1/engines/<path>; the body goes to /tmp/body
  curl -s -o /tmp/body -w '%{http_code}' -H "Authorization: Bearer ${FIDICE_TOKEN}" "http://catalog:8181/v1/engines/$1"
}

/opt/flink/bin/start-cluster.sh >/dev/null
trap '/opt/flink/bin/stop-cluster.sh >/dev/null 2>&1 || true' EXIT
for _ in $(seq 1 60); do
  if curl -fs http://localhost:8081/taskmanagers | grep -q '"taskmanagers":\[{'; then break; fi
  sleep 1
done

sql tables.sql

ns='namespaces/m8%1Fdeep%1Fflink'
if [ "$(catalog "$ns")" = 200 ]; then echo "RESULT flink ns.create m8.deep.flink"; fi
for v in 2 3; do
  if [ "$(catalog "$ns/tables/t_v$v")" = 200 ]; then
    echo "RESULT flink v$v.create $(grep -o '"format-version" *: *[0-9]*' /tmp/body | grep -o '[0-9]*$')"
  fi
done

sql drop.sql

for v in 2 3; do
  if [ "$(catalog "$ns/tables/t_v$v")" = 404 ]; then echo "RESULT flink v$v.drop ok"; fi
done
if [ "$(catalog "$ns")" = 404 ]; then echo "RESULT flink ns.drop ok"; fi
