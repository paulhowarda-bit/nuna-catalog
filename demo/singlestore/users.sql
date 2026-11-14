-- The demo's people as SingleStore users, for Studio (localhost:8183), with their Keycloak passwords. demo.sh
-- runs this as root on every up; a new SingleStore pod starts without them. gary and oscar administer
-- SingleStore; ana reads, writes, creates, alters and drops in every database; stella reads every database.
-- root stays for demo.sh and the pipelines.
CREATE USER IF NOT EXISTS 'oscar'@'%' IDENTIFIED BY 'oscar-demo';
GRANT ALL PRIVILEGES ON *.* TO 'oscar'@'%' WITH GRANT OPTION;
CREATE USER IF NOT EXISTS 'gary'@'%' IDENTIFIED BY 'gary-demo';
GRANT ALL PRIVILEGES ON *.* TO 'gary'@'%' WITH GRANT OPTION;
CREATE USER IF NOT EXISTS 'ana'@'%' IDENTIFIED BY 'ana-demo';
GRANT SELECT, SHOW VIEW, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP ON *.* TO 'ana'@'%';
CREATE USER IF NOT EXISTS 'stella'@'%' IDENTIFIED BY 'stella-demo';
GRANT SELECT, SHOW VIEW ON *.* TO 'stella'@'%';
