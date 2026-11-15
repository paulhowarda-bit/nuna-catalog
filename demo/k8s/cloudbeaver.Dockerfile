# CloudBeaver Community without its version check, signing people in only through Keycloak, and with the program
# that adds the demo's users. Context: a folder holding demo/CloudBeaverUsers.java and cloudbeaver-signin.conf
# (demo.sh copies them there). Builds offline.
#
# The version check: the product plugin names https://dbeaver.com/product/cloudbeaver-ce-version.json as
# versionUpdateURL, and the Administration page has the browser fetch it. No setting turns that off; without the
# property the page fetches nothing.
FROM dbeaver/cloudbeaver:26.2.4
RUN cd /tmp \
 && plugin=$(ls /opt/cloudbeaver/server/plugins/io.cloudbeaver.product.ce_*.jar) \
 && jar xf "$plugin" plugin.xml \
 && sed -i '/versionUpdateURL/d' plugin.xml \
 && ! grep -q versionUpdateURL plugin.xml \
 && jar uf "$plugin" plugin.xml \
 && rm plugin.xml
# The sign-in. Community has no OIDC sign-in, but it takes a user from a reverse proxy's headers (provider
# reverseProxy): the proxy beside it in the pod (cloudbeaver.yaml) signs people in through Keycloak and names
# them, and their CloudBeaver teams, in the two headers cloudbeaver-signin.conf names. CloudBeaver trusts those
# headers from anyone who reaches it, and X-Role and X-Role-TE too, which the proxy drops; so it listens on
# 127.0.0.1 only, where nothing but the proxy and the postStart hook reach it, and its own username-and-password
# sign-in (provider local) is off. A user CloudBeaver does not know (presenter, say) is refused, not added. Its
# Logout goes to the proxy's sign-out page (logout-url), which ends CloudBeaver's session before the proxy's.
COPY cloudbeaver-signin.conf /tmp/
RUN conf=/opt/cloudbeaver/conf/cloudbeaver.conf \
 && sed -i 's|^\( *\)serverPort: .*|&\n\1serverHost: "127.0.0.1",|' "$conf" \
 && sed -i '/enabledAuthProviders: \[/{n;s/"local"/"reverseProxy"/}' "$conf" \
 && sed -i '/^    app: {$/r /tmp/cloudbeaver-signin.conf' "$conf" \
 && grep -q 'serverHost: "127.0.0.1"' "$conf" \
 && grep -A1 'enabledAuthProviders: \[' "$conf" | grep -q '"reverseProxy"' \
 && ! grep -q '"local"' "$conf" \
 && grep -q 'provider: "reverseProxy"' "$conf" \
 && rm /tmp/cloudbeaver-signin.conf
# The users: cloudbeaver.yaml's postStart hook runs java -cp /opt/keel-demo CloudBeaverUsers.
COPY CloudBeaverUsers.java /opt/keel-demo/
RUN javac -d /opt/keel-demo /opt/keel-demo/CloudBeaverUsers.java
