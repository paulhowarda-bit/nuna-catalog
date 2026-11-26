#!/usr/bin/env bash
# The catalog's Helm chart on a local Kubernetes cluster, with throwaway backing services. See deploy/README.md.
#
#   deploy/local/run.sh up      build the jar and image, start PostgreSQL, Kafka and Silo, install the chart
#   deploy/local/run.sh smoke   check it through each server: config, namespace, table, vended credentials, deletes
#   deploy/local/run.sh worker  after up: the maintenance worker's chart, with Keycloak for its sign-in
#   deploy/local/run.sh down    uninstall and delete the namespaces
#
# Needs bash (Git Bash on Windows), Podman, kind, kubectl, helm, curl and JDK 25. Works on the kind cluster on Podman
# (deploy/kind/kind.sh), which up starts or creates and puts the images it builds on; kubectl and helm are always
# pointed at NUNA_K8S_CONTEXT (default kind-nuna), never at the current context. Test keys and tokens, and every password and key it uses
# (values; none is in the repository), are kept in NUNA_K8S_HOME
# (default ~/.nuna-k8s).
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=../kind/kind.sh
source "$REPO/deploy/kind/kind.sh"
CONTEXT="${NUNA_K8S_CONTEXT:-$KIND_CONTEXT}"
NS="${NUNA_K8S_NAMESPACE:-nuna-local}"
RUN="${NUNA_K8S_HOME:-$HOME/.nuna-k8s}"
RELEASE=nuna-catalog
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
# The maintenance worker in a namespace of its own, and its compaction Jobs in another (D30).
WORKER=nuna-maintenance
WORKER_NS="$NS-maintenance"
JOBS_NS="$NS-compaction"
SPARK_IMAGE=nuna-engines/spark:4.1.4-iceberg-1.12.0
# The catalog's and the worker's own Silo users, as values.yaml and worker-values.yaml name them (D21). Silo's root
# user, whom no policy binds, only manages users here.
CATALOG_USER=nuna-catalog
# The passwords and keys, made once per machine (values; backlog 282).
SECRETS="$RUN/secrets"

k() { kubectl --context "$CONTEXT" -n "$NS" "$@"; }
say() { printf '\n== %s\n' "$*"; }

secret_of() { tr -d '\r\n' < "$SECRETS/$1"; }

# Every password and key the runner uses, made once per machine in $SECRETS, one file each without a newline, none
# in the repository (backlog 282), and published as Secret nuna-local-secrets (backing.yaml, keycloak.yaml):
#   postgres-password     the catalog's database user, catalog: the runner takes the documented production path, a
#                         password (deploy/README.md), passed to the chart by --set-file
#   silo-root             Silo's root key, user nunademo
#   catalog-silo          the catalog's Silo user's key, $CATALOG_USER (--set-file)
#   worker-silo           the worker's Silo user's key, $WORKER (--set-file)
#   worker-client-secret  the worker's Keycloak client secret (keycloak.yaml, and the worker's --set-file)
#   keycloak-admin        Keycloak's administrator, admin
values() {
  local name args=()
  mkdir -p "$SECRETS"
  for name in postgres-password silo-root catalog-silo worker-silo worker-client-secret keycloak-admin; do
    [[ -s "$SECRETS/$name" ]] || openssl rand -hex 24 | tr -d '\r\n' > "$SECRETS/$name"
    args+=("--from-file=$name=$SECRETS/$name")
  done
  k create secret generic nuna-local-secrets "${args[@]}" --dry-run=client -o yaml | k apply -f - >/dev/null
}

# Forwards a free local port to port $2 of $1 (a pod or svc/<name>) and sets $port to it. kubectl itself runs in the
# background, so $! is the process to stop. They are stopped when the script exits, since a failed call exits it
# (set -e) without returning: the list is global so the trap still sees it then.
forwards=()
trap 'kill "${forwards[@]}" 2>/dev/null || true' EXIT
forward() {
  local log
  log=$(mktemp)
  kubectl --context "$CONTEXT" -n "$NS" port-forward "$1" ":$2" >"$log" 2>&1 &
  forwards+=($!)
  port=
  for _ in $(seq 1 30); do
    port=$(sed -n 's/^Forwarding from 127\.0\.0\.1:\([0-9]*\) .*/\1/p' "$log")
    [[ -n "$port" ]] && break
    sleep 1
  done
  [[ -n "$port" ]] || { echo "port-forward to $1 did not start: $(cat "$log")" >&2; return 1; }
  rm -f "$log"
}

# Gives Silo user $1, made with the secret key in $SECRETS/$2 if it is new, the user policy named $3, whose text comes
# on stdin. Runs in the Silo pod as Silo's root user, with its key from the pod's environment; the user's key goes
# on stdin too, never on a command line. May run again: re-adding a user keeps the policies attached to it.
silo_policy() {
  { secret_of "$2"; echo; cat; } | k exec -i deployment/silo -- sh -ec "
    read -r key
    cat > /tmp/$3.json
    mc alias set local http://localhost:9000 nunademo \"\$MINIO_ROOT_PASSWORD\" >/dev/null
    mc admin user add local $1 \"\$key\" >/dev/null
    mc admin policy create local $3 /tmp/$3.json
    mc admin policy detach local $3 --user $1 >/dev/null 2>&1 || true
    mc admin policy attach local $3 --user $1"
}

# The text of the MinIO user policy whose $1 (except or to) names the worker, from the catalog's delete policies in
# $2: a JSON string there, unescaped.
user_policy() {
  sed -nE 's/.*"'"$1"'":"'"$WORKER"'","policy":"((\\"|[^"])*)".*/\1/p' <<<"$2" | sed 's/\\"/"/g'
}

up() {
  say "building the server jar (no tests run) and the image nuna-catalog:local"
  (cd "$REPO" && ./gradlew -q :catalog-server:bootJar)
  podman build -q -f "$REPO/deploy/image/Dockerfile" -t nuna-catalog:local "$REPO/catalog-server/build/libs"
  if [[ "$CONTEXT" == "$KIND_CONTEXT" ]]; then
    kind_up
    kind_load nuna-catalog:local
  fi

  say "minting the test issuer's key and tokens into $RUN"
  mkdir -p "$RUN"
  "$JAVA" "$REPO/demo/Mint.java" "$RUN"

  say "starting PostgreSQL, Kafka and Silo in namespace $NS (context $CONTEXT)"
  kubectl --context "$CONTEXT" create namespace "$NS" --dry-run=client -o yaml | kubectl --context "$CONTEXT" apply -f -
  k create configmap nuna-idp --from-file=keys.json="$RUN/idp/keys.json" --dry-run=client -o yaml | k apply -f -
  values
  k apply -f "$REPO/deploy/local/backing.yaml"
  k wait --for=condition=available --timeout=5m deployment/postgres deployment/kafka deployment/silo
  k wait --for=condition=complete --timeout=3m job/seed

  say "the catalog's own Silo user, $CATALOG_USER, which may read, write and list beneath the profile's prefix"
  # Its vended credentials are cut down from these rights. Delete is in them on purpose, as some other grant may give
  # it elsewhere: the delete policy with except, applied once the catalog is up to give it, takes it away again
  # (D21), so the catalog's refused delete in smoke is that policy's doing.
  silo_policy "$CATALOG_USER" catalog-silo "$CATALOG_USER" <<'JSON'
{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":["s3:GetObject","s3:PutObject","s3:DeleteObject","s3:AbortMultipartUpload","s3:ListMultipartUploadParts"],"Resource":["arn:aws:s3:::warehouse/tables/*"]},{"Effect":"Allow","Action":["s3:ListBucket"],"Resource":["arn:aws:s3:::warehouse"]}]}
JSON

  say "installing the chart"
  # The store by a name that resolves in every namespace: the catalog vends its endpoint with each table's
  # credentials, and the maintenance worker and its compaction Jobs read tables from namespaces of their own.
  helm --kube-context "$CONTEXT" -n "$NS" upgrade --install "$RELEASE" "$REPO/deploy/helm/nuna-catalog" \
    -f "$REPO/deploy/local/values.yaml" --set "config.nuna.storage.profiles.silo.endpoint=http://silo.$NS.svc:9000" \
    --set-file "secrets.values.spring\.datasource\.password=$SECRETS/postgres-password" \
    --set-file "secrets.values.nuna\.storage\.profiles\.silo\.secret-access-key=$SECRETS/catalog-silo" \
    --wait --timeout 10m
  # A new image under the same tag is not a change Helm sees.
  k rollout restart deployment/"$RELEASE"
  k rollout status deployment/"$RELEASE" --timeout=10m

  say "the delete policies the catalog gives (D21): with except to $CATALOG_USER, with to the worker's own user"
  # As a platform operator (oscar) applies them, from GET /management/v1/maintenance/delete-policies. The worker's
  # user, $WORKER, is made here, before its chart (run.sh worker), so smoke can delete as it.
  local policies except to
  forward "svc/$RELEASE" 8181
  policies=$(curl -sf -H "Authorization: Bearer $(tr -d '\r\n' < "$RUN/tokens/oscar.jwt")" \
    "http://localhost:$port/management/v1/maintenance/delete-policies") || true
  except=$(user_policy except "$policies")
  to=$(user_policy to "$policies")
  [[ -n "$except" && -n "$to" ]] || { echo "the catalog gave no user policies for $WORKER: $policies" >&2; return 1; }
  echo "except $WORKER: $except"
  silo_policy "$CATALOG_USER" catalog-silo nuna-no-delete <<<"$except"
  echo "to $WORKER: $to"
  silo_policy "$WORKER" worker-silo "$WORKER" <<<"$to"

  say "registering Trino's client, trino-demo, as a trusted engine (as oscar, as demo/demo.sh does)"
  # Its token's group holds no grant, so only as a registered engine (D17) does it see lakehouse and read its
  # config (D18, item 84), as smoke does. Registering it again leaves the registry as it was.
  call "$port" oscar PUT /management/v1/engines '{"issuer": "https://idp.demo/nuna", "client-id": "trino-demo"}' 204

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

# One S3 request to Silo on a local port as $who, signed by curl (SigV4) with an access key, its secret key and, for
# vended credentials, their session token; fails the script on anything but the expected status (403: refused).
s3() {
  local port=$1 who=$2 key=$3 secret=$4 token=$5 method=$6 object=$7 expect=$8
  local out status args
  out=$(mktemp)
  args=(-s -o "$out" -w '%{http_code}' -X "$method" --aws-sigv4 aws:amz:us-east-1:s3 --user "$key:$secret"
    "http://localhost:$port/$object")
  [[ -n "$token" ]] && args+=(-H "x-amz-security-token: $token")
  [[ "$method" == PUT ]] && args+=(--data-binary "written by run.sh smoke")
  status=$(curl "${args[@]}" || true)
  if [[ "$status" != "$expect" ]]; then
    echo "FAIL: $who $method $object answered $status, expected $expect: $(head -c 600 "$out")" >&2
    rm -f "$out"
    return 1
  fi
  echo "ok   $status  $who $method $object"
  rm -f "$out"
}

# A string field of the last response, the last if it occurs more than once.
field() { sed -nE 's/.*"'"$1"'":"([^"]*)".*/\1/p' "$RUN/last-response.json"; }

smoke() {
  local pods ports=() ns table key secret token object
  mapfile -t pods < <(k get pods -l app.kubernetes.io/instance="$RELEASE" --field-selector=status.phase=Running -o name)
  (( ${#pods[@]} >= 2 )) || { echo "need two running servers, found ${#pods[@]}" >&2; return 1; }
  for pod in "${pods[@]:0:2}"; do
    forward "$pod" 8181
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
  # Who may delete beneath the table (D21): the worker's own Silo user, not the table's vended credentials nor the
  # catalog's own user, whom the delete policy with except binds. A writer's vended credentials put the file.
  call "$b" lake GET "/v1/lakehouse/namespaces/$ns/tables/events" "" 200 "X-Iceberg-Access-Delegation: vended-credentials"
  key=$(field s3.access-key-id) secret=$(field s3.secret-access-key) token=$(field s3.session-token)
  object=$(field location)
  object=${object#s3://}/data/smoke-probe
  forward svc/silo 9000
  echo "silo on localhost:$port"
  s3 "$port" "lake's vended credentials" "$key" "$secret" "$token" PUT "$object" 200
  s3 "$port" "lake's vended credentials" "$key" "$secret" "$token" DELETE "$object" 403
  s3 "$port" "$CATALOG_USER" "$CATALOG_USER" "$(secret_of catalog-silo)" "" DELETE "$object" 403
  s3 "$port" "lake's vended credentials" "$key" "$secret" "$token" GET "$object" 200
  s3 "$port" "$WORKER" "$WORKER" "$(secret_of worker-silo)" "" DELETE "$object" 204
  call "$b" lake DELETE "/v1/lakehouse/namespaces/$ns/tables/events" "" 204
  call "$a" lake DELETE "/v1/lakehouse/namespaces/$ns" "" 204
  echo "smoke passed on ${pods[0]#pod/} and ${pods[1]#pod/}"
}

# The maintenance worker (D30) beside the catalog, with its own identity everywhere: a client of its own at an
# identity provider (Keycloak, the client-credentials grant), its own Silo user with the delete policy the catalog
# gives for it (run.sh up makes both), and the chart's service account, which may create compaction Jobs in their own
# namespace and nothing else.
worker() {
  say "building the worker jar (no tests run) and the image nuna-maintenance:local"
  (cd "$REPO" && ./gradlew -q :catalog-maintenance:bootJar)
  podman build -q -f "$REPO/deploy/image/maintenance.Dockerfile" -t nuna-maintenance:local \
    "$REPO/catalog-maintenance/build/libs"
  podman image exists "$SPARK_IMAGE" || {
    echo "no image $SPARK_IMAGE for the compaction Jobs: podman build -t $SPARK_IMAGE" \
      "catalog-tests-engines/images/spark" >&2
    return 1
  }
  if [[ "$CONTEXT" == "$KIND_CONTEXT" ]]; then
    kind_load nuna-maintenance:local "$SPARK_IMAGE"
  fi

  say "starting Keycloak, the worker's identity provider, in namespace $NS"
  k apply -f "$REPO/deploy/local/keycloak.yaml"
  k wait --for=condition=available --timeout=5m deployment/keycloak

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
    --set-file "secrets.values.nuna\.maintenance\.token\.client-secret=$SECRETS/worker-client-secret" \
    --set-file "secrets.values.nuna\.maintenance\.storage\.silo\.secret-access-key=$SECRETS/worker-silo" \
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
