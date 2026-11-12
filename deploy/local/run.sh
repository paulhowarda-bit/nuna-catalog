#!/usr/bin/env bash
# The catalog's Helm chart on a local Kubernetes cluster, with throwaway backing services. See deploy/README.md.
#
#   deploy/local/run.sh up      build the jar and image, start PostgreSQL, Kafka and Silo, install the chart
#   deploy/local/run.sh smoke   check it through each server: config, namespace, table, vended credentials
#   deploy/local/run.sh down    uninstall and delete the namespace
#
# Needs bash (Git Bash on Windows), Docker, kubectl, helm, curl and JDK 25. Works on Docker Desktop's cluster,
# which runs images from the local Docker engine; kubectl and helm are always pointed at FIDICE_K8S_CONTEXT
# (default docker-desktop), never at the current context. Test keys and tokens are kept in FIDICE_K8S_HOME
# (default ~/.fidice-k8s).
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CONTEXT="${FIDICE_K8S_CONTEXT:-docker-desktop}"
NS="${FIDICE_K8S_NAMESPACE:-fidice-local}"
RUN="${FIDICE_K8S_HOME:-$HOME/.fidice-k8s}"
RELEASE=fidice-catalog
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"

k() { kubectl --context "$CONTEXT" -n "$NS" "$@"; }
say() { printf '\n== %s\n' "$*"; }

up() {
  say "building the server jar (no tests run) and the image fidice-catalog:local"
  (cd "$REPO" && ./gradlew -q :catalog-server:bootJar)
  docker build -q -f "$REPO/deploy/image/Dockerfile" -t fidice-catalog:local "$REPO/catalog-server/build/libs"

  say "minting the test issuer's key and tokens into $RUN"
  mkdir -p "$RUN"
  "$JAVA" "$REPO/demo/Mint.java" "$RUN"

  say "starting PostgreSQL, Kafka and Silo in namespace $NS (context $CONTEXT)"
  kubectl --context "$CONTEXT" create namespace "$NS" --dry-run=client -o yaml | kubectl --context "$CONTEXT" apply -f -
  k create configmap fidice-idp --from-file=keys.json="$RUN/idp/keys.json" --dry-run=client -o yaml | k apply -f -
  k apply -f "$REPO/deploy/local/backing.yaml"
  k wait --for=condition=available --timeout=5m deployment/postgres deployment/kafka deployment/silo
  k wait --for=condition=complete --timeout=3m job/seed

  say "installing the chart"
  helm --kube-context "$CONTEXT" -n "$NS" upgrade --install "$RELEASE" "$REPO/deploy/helm/fidice-catalog" \
    -f "$REPO/deploy/local/values.yaml" --wait --timeout 10m
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
  local pods forwards=() ports=() log port ns table
  mapfile -t pods < <(k get pods -l app.kubernetes.io/instance="$RELEASE" --field-selector=status.phase=Running -o name)
  (( ${#pods[@]} >= 2 )) || { echo "need two running servers, found ${#pods[@]}" >&2; return 1; }
  # Each server on a free local port. kubectl itself runs in the background, so $! is the process to stop.
  trap 'kill "${forwards[@]}" 2>/dev/null || true' RETURN
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
  call "$a" trino POST /v1/lakehouse/namespaces "{\"namespace\":[\"$ns\"]}"
  call "$b" ana GET "/v1/lakehouse/namespaces/$ns"
  call "$b" trino POST "/v1/lakehouse/namespaces/$ns/tables" "$table"
  call "$a" ana GET "/v1/lakehouse/namespaces/$ns/tables/events" "" 200 "X-Iceberg-Access-Delegation: vended-credentials"
  grep -q '"s3.access-key-id"' "$RUN/last-response.json" \
    && echo "ok   the load on :$a carried vended credentials" \
    || { echo "FAIL: no vended credentials in the load" >&2; return 1; }
  call "$b" trino DELETE "/v1/lakehouse/namespaces/$ns/tables/events" "" 204
  call "$a" trino DELETE "/v1/lakehouse/namespaces/$ns" "" 204
  echo "smoke passed on ${pods[0]#pod/} and ${pods[1]#pod/}"
}

case "${1:-}" in
  up) up ;;
  smoke) smoke ;;
  down)
    helm --kube-context "$CONTEXT" -n "$NS" uninstall "$RELEASE" --wait || true
    kubectl --context "$CONTEXT" delete namespace "$NS" --wait=true
    ;;
  *)
    sed -n '2,11p' "$0"
    exit 2
    ;;
esac
