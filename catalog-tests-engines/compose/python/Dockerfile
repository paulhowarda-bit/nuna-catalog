FROM python:3.12-slim-bookworm
RUN pip install --no-cache-dir "pyiceberg[pyarrow]==0.12.0" "duckdb==2.0.0"
# DuckDB fetches extensions on first use; install them at build time so a run needs no download.
RUN python -c "import duckdb; c = duckdb.connect(); [c.execute('INSTALL ' + e) for e in ('iceberg', 'httpfs', 'avro')]"
