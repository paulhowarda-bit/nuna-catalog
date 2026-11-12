#!/usr/bin/env bash
# Kafka Connect with the Apache Iceberg sink against the catalog. Records produced to the topic m8-kafka-events
# on the stack's broker are committed by the sink into m8.deep.kafka.t_events, a table (and namespace) it
# creates through the catalog and writes with vended credentials. A second batch adds a field, which the sink
# adds as a column. Connect only writes, so this asks the catalog's REST API (same token) what the table holds,
# then stops Connect and drops the table and namespace through the API.
set -euo pipefail
TOPIC=m8-kafka-events
NS='namespaces/m8%1Fdeep%1Fkafka'
TABLE="$NS/tables/t_events"

catalog() { java -cp /opt/tools Catalog "$@"; }

# The newest snapshot's total-records (snapshots are listed oldest first), or 0 before the first commit.
records() {
  if [ "$(catalog GET "$TABLE")" != 200 ]; then echo 0; return; fi
  grep -o '"total-records" *: *"[0-9]*"' /tmp/body | tail -1 | grep -o '[0-9]*' || echo 0
}

await_records() { # waits up to three minutes for the table to hold $1 records
  for _ in $(seq 1 90); do
    if [ "$(records)" = "$1" ]; then return 0; fi
    sleep 2
  done
  echo "the sink did not commit $1 records" >&2
  exit 1
}

produce() { printf '%s\n' "$@" | /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:9092 --topic "$TOPIC"; }

cat > /tmp/worker.properties <<PROPS
bootstrap.servers=kafka:9092
key.converter=org.apache.kafka.connect.storage.StringConverter
value.converter=org.apache.kafka.connect.json.JsonConverter
value.converter.schemas.enable=false
offset.storage.file.filename=/tmp/connect.offsets
# Connect polls an idle sink task about once per offset flush (60 s by default), and the Iceberg sink polls its
# control topic only then; at that pace its control consumer misses the group join and every commit times out.
offset.flush.interval.ms=1000
plugin.path=/opt/connect-plugins
listeners=http://localhost:8083
PROPS
cat > /tmp/iceberg-sink.properties <<PROPS
name=m8-iceberg-sink
connector.class=org.apache.iceberg.connect.IcebergSinkConnector
tasks.max=1
topics=$TOPIC
iceberg.tables=m8.deep.kafka.t_events
iceberg.tables.auto-create-enabled=true
iceberg.tables.evolve-schema-enabled=true
iceberg.control.topic=m8-kafka-control
iceberg.control.commit.interval-ms=2000
iceberg.catalog.type=rest
iceberg.catalog.uri=http://fidice-catalog:8181
iceberg.catalog.warehouse=engines
iceberg.catalog.token=${FIDICE_TOKEN}
iceberg.catalog.header.X-Iceberg-Access-Delegation=vended-credentials
iceberg.catalog.io-impl=org.apache.iceberg.aws.s3.S3FileIO
PROPS

/opt/kafka/bin/connect-standalone.sh /tmp/worker.properties /tmp/iceberg-sink.properties > /tmp/connect.log 2>&1 &
CONNECT=$!
trap 'kill $CONNECT 2>/dev/null || true; echo "--- connect.log (tail)"; tail -40 /tmp/connect.log' EXIT

produce '{"id":1,"name":"a"}' '{"id":2,"name":"b"}' '{"id":3,"name":"c"}'
await_records 3
if [ "$(catalog GET "$NS")" = 200 ]; then echo "RESULT kafka ns.create m8.deep.kafka"; fi
catalog GET "$TABLE" >/dev/null
echo "RESULT kafka t.create $(grep -o '"format-version" *: *[0-9]*' /tmp/body | head -1 | grep -o '[0-9]*$')"
echo "RESULT kafka t.write $(records)"

produce '{"id":4,"name":"d","note":"added"}'
await_records 4
catalog GET "$TABLE" >/dev/null
if grep -q '"name" *: *"note"' /tmp/body; then echo "RESULT kafka t.evolve 4 note"; fi

kill $CONNECT
wait $CONNECT 2>/dev/null || true
trap - EXIT

if [ "$(catalog DELETE "$TABLE?purgeRequested=true")" = 204 ] && [ "$(catalog GET "$TABLE")" = 404 ]; then
  echo "RESULT kafka t.drop ok"
fi
if [ "$(catalog DELETE "$NS")" = 204 ] && [ "$(catalog GET "$NS")" = 404 ]; then echo "RESULT kafka ns.drop ok"; fi
