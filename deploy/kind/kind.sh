#!/usr/bin/env bash
# nuna's local Kubernetes cluster: kind cluster nuna on Podman, context kind-nuna (backlog 287; Docker Desktop's
# Kubernetes until 2026-11-26). The demo (demo/demo.sh), the chart's local install (deploy/local/run.sh), the
# overnight run and the tests that run pods (catalog-maintenance's compaction tests, the engine matrix) all use it.
# The scripts source this file; the tests run it:
#
#   deploy/kind/kind.sh up             create the cluster if there is none, start it if it is stopped, wait for it
#   deploy/kind/kind.sh load IMAGE...  put images from Podman's store on the node, each unless it is there already
#
# The cluster is one node, container nuna-control-plane in the Podman machine, made from cluster.yaml. Its pods run
# images only from the node's own store, never pulled from a registry by the demo's manifests (imagePullPolicy
# Never): load puts them there, under the name a pod gives (nuna-catalog:demo is docker.io/library/nuna-catalog:demo
# to the node, where Podman's own name for a local build is localhost/nuna-catalog:demo). The node keeps everything
# under /var, the volumes' data (StorageClass standard, under /var/local-path-provisioner) and the images, on a
# Podman volume, which a restart of the node, the Podman machine or Windows keeps, and kind delete cluster deletes.
# Pods reach this machine as host.docker.internal, which up adds to the cluster's DNS. Up also lifts the task limits
# Podman and the node's systemd would put on the node and its containers.
#
# Needs podman, kubectl, and kind to create the cluster.

KIND_CLUSTER=nuna
KIND_CONTEXT=kind-$KIND_CLUSTER
KIND_NODE=$KIND_CLUSTER-control-plane
KIND_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# The name the node gives an image a pod names: Docker Hub's, for a name without a registry.
kind_ref() {
  local first=${1%%/*}
  if [[ $1 != */* ]]; then
    echo "docker.io/library/$1"
  elif [[ $first == *.* || $first == *:* || $first == localhost ]]; then
    echo "$1"
  else
    echo "docker.io/$1"
  fi
}

kind_up() {
  if [[ "$(podman machine inspect --format '{{.State}}' 2>/dev/null)" != running ]]; then
    echo "starting the Podman machine" >&2
    podman machine start >/dev/null
  fi
  # The machine's kernel keeps a large free reserve and compacts eagerly (Paul, 2026-11-28; backlog 343): with
  # the defaults, CockroachDB's three nodes broke its free memory into small pieces and the kernel could not open
  # the channels that carry Windows' connections in ("page allocation failure: order:7"), so tests timed out
  # reading. Written once; /etc/sysctl.d is read at every boot.
  podman machine ssh "test -f /etc/sysctl.d/90-nuna-memory.conf || { printf 'vm.min_free_kbytes = 262144\nvm.compaction_proactiveness = 50\n' | sudo tee /etc/sysctl.d/90-nuna-memory.conf >/dev/null && sudo sysctl -q -p /etc/sysctl.d/90-nuna-memory.conf; }" >/dev/null 2>&1 || true
  case "$(podman container inspect --format '{{.State.Status}}' "$KIND_NODE" 2>/dev/null)" in
    running) ;;
    "")
      echo "creating kind cluster $KIND_CLUSTER on Podman (once; deploy/kind/cluster.yaml)" >&2
      KIND_EXPERIMENTAL_PROVIDER=podman kind create cluster --config "$(cygpath -m "$KIND_DIR/cluster.yaml" \
        2>/dev/null || echo "$KIND_DIR/cluster.yaml")" --wait 3m >&2
      ;;
    *) podman start "$KIND_NODE" >/dev/null ;;
  esac
  # A node just started refuses connections until its API server is up.
  local try
  for try in $(seq 1 60); do
    kubectl --context "$KIND_CONTEXT" get nodes >/dev/null 2>&1 && break
    sleep 3
  done
  kubectl --context "$KIND_CONTEXT" wait --for=condition=Ready "node/$KIND_NODE" --timeout=3m >/dev/null
  kind_tasks
  kind_host_alias
}

# No limit on tasks (processes and threads together) for the node or any container in it, as under Docker Desktop
# (backlog 294). Podman starts the node with PidsLimit 2048, and systemd in the node gives each unit it starts, every
# container's scope among them, DefaultTasksMax=15% of that: 307 tasks, fewer than SingleStore and a pipeline's
# extractor JVM need. Podman keeps the lifted limit across restarts, and the node keeps its systemd drop-in in its
# own file system; containers started before the drop-in, at the cluster's creation, are lifted one by one.
kind_tasks() {
  if (( $(podman container inspect --format '{{.HostConfig.PidsLimit}}' "$KIND_NODE") > 0 )); then
    podman update --pids-limit -1 "$KIND_NODE" >/dev/null
  fi
  podman exec "$KIND_NODE" sh -ec '
    conf=/etc/systemd/system.conf.d/nuna-tasks.conf
    if [ ! -f $conf ]; then
      mkdir -p /etc/systemd/system.conf.d
      printf "[Manager]\nDefaultTasksMax=infinity\n" >$conf
      systemctl daemon-reload
    fi
    for f in $(find /sys/fs/cgroup/kubelet.slice -path "*/cri-containerd-*.scope/pids.max"); do
      [ "$(cat "$f")" = max ] || systemctl set-property --runtime "$(basename "$(dirname "$f")")" TasksMax=infinity
    done'
}

# host.docker.internal in the cluster's DNS: this machine, as Docker Desktop's pods had it. With the Podman
# machine's user-mode networking (podman machine set --user-mode-networking=true), 192.168.127.254 is this
# machine's localhost, which reaches both programs here (a test's catalog server) and the ports Podman publishes (a
# test's S3 store). Without it no one address reaches both: Podman's host.containers.internal reaches programs
# here, if Windows' firewall lets them, and changes when the machine restarts, so up looks again each time.
kind_host_alias() {
  local ip corefile
  if [[ "$(podman machine inspect --format '{{.UserModeNetworking}}' 2>/dev/null)" == true ]]; then
    ip=192.168.127.254
  else
    echo "the Podman machine runs without user-mode networking: pods reach this machine's programs but not the" \
      "ports Podman publishes on it (podman machine set --user-mode-networking=true, with the machine stopped)" >&2
    ip=$(podman exec "$KIND_NODE" getent hosts host.containers.internal | awk '{print $1; exit}')
  fi
  [[ -n "$ip" ]] || return 0
  corefile=$(kubectl --context "$KIND_CONTEXT" -n kube-system get configmap coredns -o jsonpath='{.data.Corefile}')
  grep -q " $ip host.docker.internal$" <<<"$corefile" && return 0
  corefile=$(awk -v ip="$ip" '
    /^ *hosts \{/ { skip = 1; next }
    skip && /^ *\}/ { skip = 0; next }
    skip { next }
    { print }
    /^\.:53 \{/ { print "    hosts {"; print "       " ip " host.docker.internal"; print "       fallthrough"; print "    }" }
  ' <<<"$corefile")
  kubectl --context "$KIND_CONTEXT" -n kube-system create configmap coredns --from-literal=Corefile="$corefile" \
    --dry-run=client -o yaml | kubectl --context "$KIND_CONTEXT" replace -f - >/dev/null
  # Restarted rather than left to reload the file, which each of its pods does in its own time.
  kubectl --context "$KIND_CONTEXT" -n kube-system rollout restart deployment/coredns >/dev/null
  kubectl --context "$KIND_CONTEXT" -n kube-system rollout status deployment/coredns --timeout=2m >/dev/null
}

kind_load() {
  local image ref id
  for image in "$@"; do
    ref=$(kind_ref "$image")
    id=$(podman image inspect --format '{{.Id}}' "$image")
    if [[ "$(podman exec "$KIND_NODE" crictl inspecti -o go-template --template '{{.status.id}}' "$ref" 2>/dev/null)" \
        == "sha256:$id" ]]; then
      continue
    fi
    podman tag "$id" "$ref"
    podman save "$ref" | podman exec -i "$KIND_NODE" ctr --namespace=k8s.io images import --all-platforms \
      --digests --snapshotter=overlayfs - >/dev/null
  done
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  set -euo pipefail
  command=${1:-}
  shift || true
  case "$command" in
    up) kind_up ;;
    load) kind_load "$@" ;;
    *) sed -n '2,20p' "$0" >&2; exit 2 ;;
  esac
fi
