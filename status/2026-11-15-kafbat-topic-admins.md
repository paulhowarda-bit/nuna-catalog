# 2026-11-15, kafbat: ana, gary and stella administer topics (backlog 31)

Changed: kafbat's role for groups demo-analysts, demo-grant-admins and demo-stewards (`demo/k8s/kafka-ui-signin.yaml`)
went from reading topics to every topic action: create, edit settings, produce, read, clear and delete messages, and
analysis. Consumer groups, schemas, connectors and the cluster's settings stay VIEW; ACLs and kafbat's own settings
stay oscar's (demo-operators). `demo/README.md` and the sign-in text `demo.sh` prints say so.

Checked on a fresh `demo/demo.sh up` from the branch: a script signed in to kafbat through Keycloak (authorization
code, as a browser does) as ana, gary and stella in turn. Each read their permissions from `/api/authorization`
(TOPIC: VIEW, CREATE, EDIT, DELETE, MESSAGES_READ, MESSAGES_PRODUCE, MESSAGES_DELETE, ANALYSIS_VIEW, ANALYSIS_RUN),
then created `demo-<name>-check`, changed its `retention.ms`, produced a message and read it back, cleared the
partition and deleted the topic: 200 each time. Deleting a consumer group and reading `/api/config` answered 403; oscar,
as a control, got past the role check on both (404 for the missing group, 400 since dynamic config is off).

Not covered: the kafbat pages themselves, clicked through in a browser; the REST API is what they call.
