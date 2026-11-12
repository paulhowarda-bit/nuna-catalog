# SingleStore's dev image with the change-feed connector where its extractor looks for connectors.
# Context: a folder holding fidice-changefeed-connector.jar (demo.sh builds it).
FROM ghcr.io/singlestore-labs/singlestoredb-dev:0.2.88
COPY fidice-changefeed-connector.jar /server/singlestoredb-server-10.9.0-d16d12a6ad/extractors/lib-extractor-connect/
