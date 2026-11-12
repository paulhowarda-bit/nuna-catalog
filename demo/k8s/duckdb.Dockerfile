# DuckDB with its UI (duckdb.yaml): the CLI and the extensions the demo uses, installed here so the pod
# downloads nothing but the UI's web page, which the UI always fetches from ui.duckdb.org. socat relays the
# UI, which listens on [::1] only, to the pod's address.
FROM debian:bookworm-slim
ARG DUCKDB_VERSION
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates socat \
    && rm -rf /var/lib/apt/lists/*
ADD https://github.com/duckdb/duckdb/releases/download/v${DUCKDB_VERSION}/duckdb-cli-linux-amd64.tar.gz /tmp/duckdb.tgz
RUN tar -xzf /tmp/duckdb.tgz -C /usr/local/bin && rm /tmp/duckdb.tgz \
    && duckdb -c "INSTALL iceberg; INSTALL httpfs; INSTALL avro; INSTALL ui;"
