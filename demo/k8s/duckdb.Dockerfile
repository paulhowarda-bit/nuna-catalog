# DuckDB with its UI (duckdb.yaml): the CLI and every extension the demo and the UI use, installed here so the
# pod downloads no extension (duckdb.yaml also turns off automatic installs). The UI still fetches its web page
# from ui.duckdb.org, so it needs the internet. Opening the UI installs autocomplete, icu and json, though the
# CLI has them built in; having them here keeps that install from downloading. socat relays the UI, which
# listens on [::1] only, to the pod's address.
FROM debian:bookworm-slim
ARG DUCKDB_VERSION
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates socat \
    && rm -rf /var/lib/apt/lists/*
ADD https://github.com/duckdb/duckdb/releases/download/v${DUCKDB_VERSION}/duckdb-cli-linux-amd64.tar.gz /tmp/duckdb.tgz
RUN tar -xzf /tmp/duckdb.tgz -C /usr/local/bin && rm /tmp/duckdb.tgz \
    && duckdb -c "INSTALL iceberg; INSTALL httpfs; INSTALL avro; INSTALL ui; \
                  INSTALL autocomplete; INSTALL icu; INSTALL json;"
