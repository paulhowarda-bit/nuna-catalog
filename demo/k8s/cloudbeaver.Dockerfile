# CloudBeaver Community without its version check, and with the program that adds the demo's users.
# Context: a folder holding demo/CloudBeaverUsers.java (demo.sh copies it there). Builds offline.
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
# The users: cloudbeaver.yaml's postStart hook runs java -cp /opt/keel-demo CloudBeaverUsers.
COPY CloudBeaverUsers.java /opt/keel-demo/
RUN javac -d /opt/keel-demo /opt/keel-demo/CloudBeaverUsers.java
