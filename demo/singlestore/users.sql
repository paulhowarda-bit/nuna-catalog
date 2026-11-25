-- The demo's people as SingleStore users, for Studio (port 8183). They have no password: each signs in
-- through Keycloak, and SingleStore checks the signed SAML response Studio passes on (singlestore.Dockerfile).
-- demo.sh runs this as root on every up; a new SingleStore pod starts without them. gary, oscar and presenter
-- administer SingleStore; ana reads, writes, creates, alters and drops in every database; stella reads every database. Both
-- also hold SHOW METADATA, which ALL PRIVILEGES gives gary and oscar.
-- root stays for demo.sh and the pipelines.
CREATE USER IF NOT EXISTS 'oscar'@'%' IDENTIFIED WITH authentication_saml;
GRANT ALL PRIVILEGES ON *.* TO 'oscar'@'%' WITH GRANT OPTION;
CREATE USER IF NOT EXISTS 'presenter'@'%' IDENTIFIED WITH authentication_saml;
GRANT ALL PRIVILEGES ON *.* TO 'presenter'@'%' WITH GRANT OPTION;
CREATE USER IF NOT EXISTS 'gary'@'%' IDENTIFIED WITH authentication_saml;
GRANT ALL PRIVILEGES ON *.* TO 'gary'@'%' WITH GRANT OPTION;
CREATE USER IF NOT EXISTS 'ana'@'%' IDENTIFIED WITH authentication_saml;
GRANT SELECT, SHOW VIEW, SHOW METADATA, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP ON *.* TO 'ana'@'%';
CREATE USER IF NOT EXISTS 'stella'@'%' IDENTIFIED WITH authentication_saml;
GRANT SELECT, SHOW VIEW, SHOW METADATA ON *.* TO 'stella'@'%';
