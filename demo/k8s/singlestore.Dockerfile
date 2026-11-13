# SingleStore's dev image with the change-feed connector where its extractor looks for connectors.
# Context: a folder holding keel-changefeed-connector.jar (demo.sh builds it).
FROM ghcr.io/singlestore-labs/singlestoredb-dev:0.2.88
COPY keel-changefeed-connector.jar /server/singlestoredb-server-10.9.0-d16d12a6ad/extractors/lib-extractor-connect/
# Studio's page otherwise loads Segment, Google Analytics, Mixpanel and Intercom into the browser after sign-in,
# and checks singlestore.com for upgrades.
USER root
RUN printf 'disableUITelemetry = true\ndisableUpgradeNotifications = true\n' >> /etc/singlestore/singlestoredb-studio.hcl
USER singlestore
