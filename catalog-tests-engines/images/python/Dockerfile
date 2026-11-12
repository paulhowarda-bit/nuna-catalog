FROM python:3.12-slim-bookworm
RUN pip install --no-cache-dir "pyiceberg[pyarrow]==0.12.0"
# The run script, baked in so a Kubernetes Job needs no volume.
COPY pyiceberg_matrix.py /scripts/
