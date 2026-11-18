# SingleStore's dev image with the change-feed connector where its extractor looks for connectors, and Studio
# signing people in through Keycloak.
# Context: a folder holding nuna-changefeed-connector.jar (demo.sh builds it).
FROM ghcr.io/singlestore-labs/singlestoredb-dev:0.2.88
# The host the browser reaches the demo on (demo.sh, NUNA_DEMO_HOST): SingleStore checks that Studio's SAML
# sign-ins were sent to https://<host>:8183, the front door's port for Studio (ports.yaml).
ARG DEMO_HOST=localhost
COPY nuna-changefeed-connector.jar /server/singlestoredb-server-10.9.0-d16d12a6ad/extractors/lib-extractor-connect/
USER root
# Studio's page otherwise loads Segment, Google Analytics, Mixpanel and Intercom into the browser after sign-in,
# and checks singlestore.com for upgrades.
RUN printf 'disableUITelemetry = true\ndisableUpgradeNotifications = true\n' >> /etc/singlestore/singlestoredb-studio.hcl
# Studio serves HTTPS, behind the front door (ports.yaml), with the same certificate (Secret demo-tls, which
# singlestore.yaml mounts): it builds the address Keycloak sends the SAML response to from the request it gets,
# and over plain HTTP that address would be http://, which Keycloak refuses.
RUN printf '%s\n' 'HTTPSCertificateFile = "/etc/nuna-demo/tls/tls.crt"' \
      'HTTPSCertificateKeyFile = "/etc/nuna-demo/tls/tls.key"' >> /etc/singlestore/singlestoredb-studio.hcl
# Studio signs people in with SAML: its sign-in page sends the browser to Keycloak (client singlestore-studio),
# which sends back a signed SAML response, and Studio signs in to SingleStore as the person with that response
# as the password. SingleStore checks it: Keycloak's signature (the realm's certificate, which singlestore.yaml
# mounts from Secret keycloak-keys), the audience, the recipient and that the name in it (the NameID, the
# Keycloak username) is the user signing in. The people are SAML users (demo/singlestore/users.sql), with no
# password of their own. Studio reads Keycloak's SAML address from the realm's metadata, inside the cluster.
RUN printf '%s\n' \
      'version = 1' \
      '' \
      'cluster "localhost" {' \
      '  name        = "Localhost"' \
      '  description = "The nuna demo'"'"'s SingleStore"' \
      '  hostname    = "localhost"' \
      '  port        = 3306' \
      '  profile     = "DEVELOPMENT"' \
      '' \
      '  samlConfig {' \
      '    samlIdpMetadaUrl      = "http://keycloak:8080/realms/nuna/protocol/saml/descriptor"' \
      '    samlAssertionAudience = "singlestore-studio"' \
      '  }' \
      '}' > /var/lib/singlestoredb-studio/studio.hcl \
 && chown singlestore:singlestore /var/lib/singlestoredb-studio/studio.hcl
USER singlestore
# SingleStore reads its SAML settings only from memsql.cnf, as it starts: the master's, in the nodes the image
# ships in /data (the start script unpacks /server/data.tgz only into an empty /data, which the demo never
# mounts). SingleStore looks for the certificate in its basedir, the server's folder; a link points it at the
# mounted one.
RUN printf '%s\n' \
      'saml_require_signature_validation = ON' \
      'saml_x509_certificate = keycloak-saml.crt' \
      'saml_assertion_audience = singlestore-studio' \
      "saml_message_recipient = https://$DEMO_HOST:8183/api/saml-assert" \
      'saml_use_NameID = ON' >> /data/master/memsql.cnf \
 && ln -s /etc/nuna-demo/keycloak-saml.crt /server/singlestoredb-server-10.9.0-d16d12a6ad/keycloak-saml.crt
