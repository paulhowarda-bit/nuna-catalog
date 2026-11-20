FROM python:3.12-slim-bookworm
RUN pip install --no-cache-dir "pyiceberg[pyarrow]==0.12.0"
# The run scripts and the audit table writer's token endpoint, baked in so a Kubernetes Job needs no volume.
COPY pyiceberg_matrix.py audit_tables_matrix.py token_endpoint.py /scripts/
