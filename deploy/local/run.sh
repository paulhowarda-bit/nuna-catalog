#!/usr/bin/env bash
# The catalog's Helm chart on a local Kubernetes cluster, with throwaway backing services. See deploy/README.md.
#
#   deploy/local/run.sh up      build the jar and image, start PostgreSQL, Kafka and Silo, install the chart
#   deploy/local/run.sh smoke   check it through each server: config, namespace, table, vended credentials
#   deploy/local/run.sh worker  after up: the maintenance worker's chart, with Keycloak for its sign-in
#   deploy/local/run.sh down    uninstall and delete the namespaces
#
# Needs bash (Git Bash on Windows), Docker, kubectl, helm, curl and JDK 25. Works on Docker Desktop's cluster,
# which runs images from the local Docker engine; kubectl and helm are always pointed at NUNA_K8S_CONTEXT
# (default docker-desktop), never at the current context. Test keys and tokens are kept in NUNA_K8S_HOME
# (default ~/.nuna-k8s).
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CONTEXT="${NUNA_K8S_CONTEXT:-docker-desktop}"
NS="${NUNA_K8S_NAMESPACE:-nuna-local}"
RUN="${NUNA_K8S_HOME:-$HOME/.nuna-k8s}"
RELEASE=nuna-catalog
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
# The maintenance worker in a namespace of its own, and its compaction Jobs in another (D30).
WORKER=nuna-maintenance
WORKER_NS="$NS-maintenance"
JOBS_NS="$NS-compaction"
SPARK_IMAGE=nuna-engines/spark:4.1.4-iceberg-1.12.0

k() { kubectl --context "$CONTEXT" -n "$NS" "$@"; }
say() { printf '\n== %s\n' "$*"; }

up() {
  say "building the server jar (no tests run) and the image nuna-catalog:local"
  (cd "$REPO" && ./gradlew -q :catalog-server:bootJar)
  docker build -q -f "$REPO/deploy/image/Dockerfile" -t nuna-catalog:local "$REPO/catalog-server/build/libs"

  say "minting the test issuer's key and tokens into $RUN"
  mkdir -p "$RUN"
  "$JAVA" "$REPO/demo/Mint.java" "$RUN"

  say "starting PostgreSQL, Kafka and Silo in namespace $NS (context $CONTEXT)"
  kubectl --context "$CONTEXT" create namespace "$NS" --dry-run=client -o yaml | kubectl --context "$CONTEXT" apply -f -
  k create configmap nuna-idp --from-file=keys.json="$RUN/idp/keys.json" --dry-run=client -o yaml | k apply -f -
  k apply -f "$REPO/deploy/local/backing.yaml"
  k wait --for=condition=available --timeout=5m deployment/postgres deployment/kafka deployment/silo
  k wait --for=condition=complete --timeout=3m job/seed

  say "installing the chart"
  # The store by a name that resolves in every namespace: the catalog vends its endpoint with each table's
  # credentials, and the maintenance worker and its compaction Jobs read tables from namespaces of their own.
  helm --kube-context "$CONTEXT" -n "$NS" upgrade --install "$RELEASE" "$REPO/deploy/helm/nuna-catalog" \
    -f "$REPO/deploy/local/values.yaml" --set "config.nuna.storage.profiles.silo.endpoint=http://silo.$NS.svc:9000" \
    --wait --timeout 10m
  # A new image under the same tag is not a change Helm sees.
  k rollout restart deployment/"$RELEASE"
  k rollout status deployment/"$RELEASE" --timeout=10m

  say "granting the demo groups their privileges (by SQL, as demo/demo.sh does)"
  k exec -i deployment/postgres -- psql -q -v ON_ERROR_STOP=1 -U catalog -d catalog <<'SQL'
INSERT INTO grant_record (catalog_id, securable_kind, securable_id, group_name, privilege, granted_by, granted_at)
SELECT c.id, 'CATALOG', c.id, g.group_name, g.privilege, 'k8s-local-setup', now()
FROM catalog c,
     (VALUES ('demo-loaders', 'create'), ('demo-loaders', 'modify'), ('demo-analysts', 'select')) AS g(group_name, privilege)
WHERE c.name_norm = 'lakehouse'
ON CONFLICT DO NOTHING;
SQL
  k get pods -o wide
}

# One request to the server on a local port; fails the script on anything but the expected status.
call() {
  local port=$1 user=$2 method=$3 path=$4 body=${5:-} expect=${6:-200} header=${7:-}
  local out status args
  out=$(mktemp)
  args=(-s -o "$out" -w '%{http_code}' -X "$method" "http://localhost:$port$path")
  [[ -n "$user" ]] && args+=(-H "Authorization: Bearer $(tr -d '\r\n' < "$RUN/tokens/$user.jwt")")
  [[ -n "$body" ]] && args+=(-H 'Content-Type: application/json' --data "$body")
  [[ -n "$header" ]] && args+=(-H "$header")
  # A refused connection is status 000, reported like any other wrong status.
  status=$(curl "${args[@]}" || true)
  if [[ "$status" != "$expect" ]]; then
    echo "FAIL: $user $method $path on :$port answered $status, expected $expect: $(head -c 600 "$out")" >&2
    rm -f "$out"
    return 1
  fi
  echo "ok   $status  ${user:-(no token)} $method $path on :$port"
  cat "$out" > "$RUN/last-response.json"
  rm -f "$out"
}

smoke() {
  local pods ports=() log port ns table
  mapfile -t pods < <(k get pods -l app.kubernetes.io/instance="$RELEASE" --field-selector=status.phase=Running -o name)
  (( ${#pods[@]} >= 2 )) || { echo "need two running servers, found ${#pods[@]}" >&2; return 1; }
  # Each server on a free local port. kubectl itself runs in the background, so $! is the process to stop. Stopped
  # when the script exits, since a failed call exits it (set -e) without returning: the list is global so the trap
  # still sees it then.
  forwards=()
  trap 'kill "${forwards[@]}" 2>/dev/null || true' EXIT
  for pod in "${pods[@]:0:2}"; do
    log=$(mktemp)
    kubectl --context "$CONTEXT" -n "$NS" port-forward "$pod" :8181 >"$log" 2>&1 &
    forwards+=($!)
    port=
    for _ in $(seq 1 30); do
      port=$(sed -n 's/^Forwarding from 127\.0\.0\.1:\([0-9]*\) .*/\1/p' "$log")
      [[ -n "$port" ]] && break
      sleep 1
    done
    [[ -n "$port" ]] || { echo "port-forward to $pod did not start: $(cat "$log")" >&2; return 1; }
    rm -f "$log"
    ports+=("$port")
    echo "server $pod on localhost:$port"
  done
  local a=${ports[0]} b=${ports[1]}
  ns="k8s_smoke_$(date +%s)"
  table='{"name":"events","schema":{"type":"struct","schema-id":0,"fields":[{"id":1,"name":"id","required":true,"type":"long"},{"id":2,"name":"at","required":false,"type":"timestamptz"}]}}'

  call "$a" "" GET /v1/config "" 401
  call "$a" trino GET "/v1/config?warehouse=lakehouse"
  call "$a" lake POST /v1/lakehouse/namespaces "{\"namespace\":[\"$ns\"]}"
  call "$b" ana GET "/v1/lakehouse/namespaces/$ns"
  call "$b" lake POST "/v1/lakehouse/namespaces/$ns/tables" "$table"
  call "$a" ana GET "/v1/lakehouse/namespaces/$ns/tables/events" "" 200 "X-Iceberg-Access-Delegation: vended-credentials"
  grep -q '"s3.access-key-id"' "$RUN/last-response.json" \
    && echo "ok   the load on :$a carried vended credentials" \
    || { echo "FAIL: no vended credentials in the load" >&2; return 1; }
  call "$b" lake DELETE "/v1/lakehouse/namespaces/$ns/tables/events" "" 204
  call "$a" lake DELETE "/v1/lakehouse/namespaces/$ns" "" 204
  echo "smoke passed on ${pods[0]#pod/} and ${pods[1]#pod/}"
}

# The maintenance worker (D30) beside the catalog, with its own identity everywhere: a client of its own at an
# identity provider (Keycloak, the client-credentials grant), its own Silo user, and the chart's service account,
# which may create compaction Jobs in their own namespace and nothing else.
worker() {
  local forward log port policy
  say "building the worker jar (no tests run) and the image nuna-maintenance:local"
  (cd "$REPO" && ./gradlew -q :catalog-maintenance:bootJar)
  docker build -q -f "$REPO/deploy/image/maintenance.Dockerfile" -t nuna-maintenance:local \
    "$REPO/catalog-maintenance/build/libs"
  docker image inspect "$SPARK_IMAGE" >/dev/null 2>&1 || {
    echo "no image $SPARK_IMAGE for the compaction Jobs: docker build -t $SPARK_IMAGE" \
      "catalog-tests-engines/images/spark" >&2
    return 1
  }

  say "starting Keycloak, the worker's identity provider, in namespace $NS"
  k apply -f "$REPO/deploy/local/keycloak.yaml"
  k wait --for=condition=available --timeout=5m deployment/keycloak

  say "the worker's own Silo user, with the delete policy the catalog gives for it"
  # The catalog runs as Silo's root user here, whom no policy binds, so the other policy (except) is not applied.
  log=$(mktemp)
  kubectl --context "$CONTEXT" -n "$NS" port-forward "svc/$RELEASE" :8181 >"$log" 2>&1 &
  forward=$!
  port=
  for _ in $(seq 1 30); do
    port=$(sed -n 's/^Forwarding from 127\.0\.0\.1:\([0-9]*\) .*/\1/p' "$log")
    [[ -n "$port" ]] && break
    sleep 1
  done
  policy=$(curl -sf -H "Authorization: Bearer $(tr -d '\r\n' < "$RUN/tokens/oscar.jwt")" \
    "http://localhost:$port/management/v1/maintenance/delete-policies") || true
  kill "$forward" 2>/dev/null || true
  rm -f "$log"
  # The user policy "to" the worker, a JSON string in the answer, unescaped.
  policy=$(sed -nE 's/.*"to":"'"$WORKER"'","policy":"((\\"|[^"])*)".*/\1/p' <<<"$policy" | sed 's/\\"/"/g')
  [[ -n "$policy" ]] || { echo "the catalog gave no delete policy for $WORKER" >&2; return 1; }
  echo "$policy"
  k exec -i deployment/silo -- sh -ec "
    cat > /tmp/$WORKER-policy.json
    mc alias set local http://localhost:9000 nunademo nunademo-secret-key >/dev/null
    mc admin user add local $WORKER nuna-maintenance-secret-key
    mc admin policy create local $WORKER /tmp/$WORKER-policy.json
    mc admin policy detach local $WORKER --user $WORKER >/dev/null 2>&1 || true
    mc admin policy attach local $WORKER --user $WORKER" <<<"$policy"

  say "granting the worker's group nuna-workers write on lakehouse"
  k exec -i deployment/postgres -- psql -q -v ON_ERROR_STOP=1 -U catalog -d catalog <<'SQL'
INSERT INTO grant_record (catalog_id, securable_kind, securable_id, group_name, privilege, granted_by, granted_at)
SELECT c.id, 'CATALOG', c.id, 'nuna-workers', 'write', 'k8s-local-setup', now()
FROM catalog c
WHERE c.name_norm = 'lakehouse'
ON CONFLICT DO NOTHING;
SQL

  say "installing the worker in namespace $WORKER_NS, its compaction Jobs in $JOBS_NS"
  for ns in "$WORKER_NS" "$JOBS_NS"; do
    kubectl --context "$CONTEXT" create namespace "$ns" --dry-run=client -o yaml | kubectl --context "$CONTEXT" apply -f -
  done
  helm --kube-context "$CONTEXT" -n "$WORKER_NS" upgrade --install "$WORKER" "$REPO/deploy/helm/nuna-maintenance" \
    -f "$REPO/deploy/local/worker-values.yaml" \
    --set "config.nuna.maintenance.catalog-uri=http://$RELEASE.$NS.svc:8181/" \
    --set "config.nuna.maintenance.token.endpoint=http://keycloak.$NS.svc:8080/realms/nuna/protocol/openid-connect/token" \
    --set "config.nuna.maintenance.storage.silo.endpoint=http://silo.$NS.svc:9000" \
    --set "config.nuna.maintenance.compaction.namespace=$JOBS_NS" \
    --wait --timeout 5m
  # A new image under the same tag is not a change Helm sees.
  kubectl --context "$CONTEXT" -n "$WORKER_NS" rollout restart deployment/"$WORKER"
  kubectl --context "$CONTEXT" -n "$WORKER_NS" rollout status deployment/"$WORKER" --timeout=5m
  kubectl --context "$CONTEXT" -n "$WORKER_NS" get pods -o wide
}

case "${1:-}" in
  up) up ;;
  smoke) smoke ;;
  worker) worker ;;
  down)
    helm --kube-context "$CONTEXT" -n "$WORKER_NS" uninstall "$WORKER" --wait 2>/dev/null || true
    helm --kube-context "$CONTEXT" -n "$NS" uninstall "$RELEASE" --wait || true
    kubectl --context "$CONTEXT" delete namespace "$JOBS_NS" "$WORKER_NS" "$NS" --ignore-not-found --wait=true
    ;;
  *)
    sed -n '2,12p' "$0"
    exit 2
    ;;
esac
