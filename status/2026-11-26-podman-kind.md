# 2026-11-26, Docker Desktop's Kubernetes replaced by kind cluster nuna on Podman (backlog 287)

- `deploy/kind/` makes the one local cluster: `cluster.yaml` (cluster `nuna`, context `kind-nuna`, one node, ports
  8180 to 8188 published on localhost, NodePorts allowed from 8180) and `kind.sh` (`up` creates or starts it and waits
  for it; `load` puts images Podman built or pulled on the node, each unless it is there already). `kind load` is not
  used: it calls the Docker CLI. Images are saved by Podman and imported into the node with `ctr`, under Docker Hub's
  name (`nuna-catalog:demo` is `docker.io/library/nuna-catalog:demo` to the node, `localhost/nuna-catalog:demo` to
  Podman).
- Pods reach this machine as `host.docker.internal`, which `kind.sh up` writes into CoreDNS as 192.168.127.254. That
  address needs the Podman machine's user-mode networking (set on this machine: `podman machine set
  --user-mode-networking=true`); it reaches both programs on Windows and the ports Podman publishes.
  `host.containers.internal` reaches only the first.
- `demo/demo.sh`, `deploy/local/run.sh`, `overnight.sh`, `CompactionCluster` and `EngineMatrixTest` default to
  `kind-nuna`, build with `podman` and load onto the node (`KindCluster` in `catalog-tests-s3` for the tests). The demo's
  front door Services are NodePorts on their own port numbers (`demo/k8s/ports.yaml`); `check_ports` went with
  LoadBalancer.
- Data: the node's `/var` is a Podman volume. PostgreSQL's and Silo's PVCs use kind's `standard` StorageClass, under
  `/var/local-path-provisioner` on it; nothing the demo keeps lives in a container's own layer.
- Checked live: the demo came up from this branch (4 min 42 s); 8180, 8181, 8184, 8185 and 8187 answered (8182 and 8186
  give 502 with Kafka off; 8186 without a password 401); the catalog's database check passed (TLS 1.3, certificate
  sign-in, no role with a password). After `podman restart nuna-control-plane` and `kind.sh up` every pod ran again,
  the catalog's rows were there and Silo's 115 files too. `KubernetesCompactionTest` (192 s) and
  `PrivateCaCompactionTest` (`-Pbites`) pass on the cluster.
- Not checked: `deploy/local/run.sh`, the engine matrix and the overnight run on the cluster; the CDC leg. Keycloak
  sign-in refused the people's passwords; the realm was imported before those passwords were reset to `<name>-demo`,
  so it is checked again after the next `up`.
- Left: the CDC source databases, CloudBeaver's workspace and Keycloak keep no volume (by design until now). Ports
  listen on 127.0.0.1 only, so an engine on another machine no longer reaches 8187.
