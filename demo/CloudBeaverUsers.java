/*
 * The demo's people in CloudBeaver, each with connections that sign in as them. The pod's postStart hook
 * (demo/k8s/cloudbeaver.yaml) runs this as the server starts, because CloudBeaver keeps its users and connections
 * inside the pod and every new pod starts without them. CloudBeaver takes its users only from Keycloak, through
 * the sign-in proxy beside it, which names the user and their CloudBeaver teams in two headers
 * (cloudbeaver.Dockerfile). This runs inside the pod, the one place besides the proxy that reaches CloudBeaver, and
 * names the administrator (CB_ADMIN_NAME) in those headers itself. It adds each user in DEMO_USERS
 * ("name:password ..."), whose password is their database password, the same as their Keycloak one. Then, for
 * every person, the administrator too, it adds four connections and grants them to that person alone:
 *
 *   nuna (Trino, as <person>)             Trino as user <person>, sending their catalog token as Trino's extra
 *                                         credential "token". Trino hands it to nuna (demo/k8s/trino.yaml), so
 *                                         the catalog sees the person.
 *   backoffice (PostgreSQL, as <person>)  the CDC leg's PostgreSQL, signed in as the person (demo/cdc/backoffice.sql)
 *   treasury (Oracle, as <person>)        the CDC leg's Oracle, signed in as the person (demo/cdc/oracle.sql)
 *   custody (CockroachDB, as <person>)    the CDC leg's CockroachDB, signed in as the person (demo/cdc/cockroach.sql)
 *
 * A person's token is the file of their name in TOKENS (Secret demo-people-tokens). Each step can run again. The
 * image compiles this when it is built (demo/k8s/cloudbeaver.Dockerfile).
 */
import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

static final URI API = URI.create("http://localhost:8978/api/gql");
static final HttpClient HTTP = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
static final Path TOKENS = Path.of("/opt/nuna-demo/tokens");
/** The project of the connections the administrator shares. */
static final String SHARED = "g_GlobalConfiguration";
/** The headers the sign-in proxy sets, as cloudbeaver.Dockerfile names them to CloudBeaver. */
static final String USER_HEADER = "X-Forwarded-Preferred-Username";
static final String TEAMS_HEADER = "X-Forwarded-Groups";

void main() throws Exception {
    signInAsAdministrator();
    String existing = call("query { listUsers(page: {offset: 0, limit: 1000}, filter: {}) { userId } }", "{}");
    Map<String, String> people = new LinkedHashMap<>();
    for (String entry : System.getenv("DEMO_USERS").trim().split("\\s+")) {
        String user = entry.substring(0, entry.indexOf(':'));
        String password = entry.substring(entry.indexOf(':') + 1);
        people.put(user, password);
        if (!values(existing, "userId").contains(user)) {
            call("query($u: ID!) { createUser(userId: $u, enabled: true) { userId } }", "{\"u\": " + json(user) + "}");
        }
    }
    people.put(System.getenv("CB_ADMIN_NAME"), System.getenv("CB_ADMIN_PASSWORD"));

    String connections = call("query { userConnections(projectId: \"" + SHARED + "\") { id name } }", "{}");
    for (Map.Entry<String, String> person : people.entrySet()) {
        String user = person.getKey();
        String password = person.getValue();
        String token = Files.readString(TOKENS.resolve(user)).trim();
        connection(connections, user, "nuna (Trino, as " + user + ")",
            "\"driverId\": \"generic:trino_jdbc\", \"configurationType\": \"MANUAL\", \"host\": \"trino\", "
                + "\"port\": \"8080\", \"properties\": {\"extraCredentials\": " + json("token:" + token) + "}, "
                + login("native", user, null));
        connection(connections, user, "backoffice (PostgreSQL, as " + user + ")",
            "\"driverId\": \"postgresql:postgres-jdbc\", \"configurationType\": \"MANUAL\", \"host\": \"backoffice\", "
                + "\"port\": \"5432\", \"databaseName\": \"backoffice\", " + login("native", user, password));
        connection(connections, user, "treasury (Oracle, as " + user + ")",
            "\"driverId\": \"oracle:oracle_thin\", \"configurationType\": \"URL\", "
                + "\"url\": \"jdbc:oracle:thin:@//oracle:1521/FREEPDB1\", " + login("oracle_native", user, password));
        // CloudBeaver Community serves no CockroachDB driver and hides connections that name one; CockroachDB
        // speaks PostgreSQL's protocol, so the PostgreSQL driver signs in.
        connection(connections, user, "custody (CockroachDB, as " + user + ")",
            "\"driverId\": \"postgresql:postgres-jdbc\", \"configurationType\": \"MANUAL\", "
                + "\"host\": \"cockroach\", \"port\": \"26257\", \"databaseName\": \"custody\", "
                + login("native", user, password));
    }
}

/** A connection's saved sign-in: the user, and the password unless it is null. */
static String login(String authModel, String user, String password) {
    return "\"authModelId\": " + json(authModel) + ", \"credentials\": {\"userName\": " + json(user)
        + (password == null ? "" : ", \"userPassword\": " + json(password)) + "}";
}

/** Adds the shared connection unless one of that name exists, and grants it to the person alone. */
void connection(String connections, String user, String name, String settings) throws Exception {
    Matcher named = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\",\\s*\"name\"\\s*:\\s*" + Pattern.quote(json(name)))
        .matcher(connections);
    String id;
    if (named.find()) {
        id = named.group(1);
    } else {
        String created = call("mutation($c: ConnectionConfig!) { createConnection(projectId: \"" + SHARED
            + "\", config: $c) { id } }", "{\"c\": {\"name\": " + json(name) + ", \"connectionType\": \"dev\", "
            + "\"saveCredentials\": true, " + settings + "}}");
        id = values(created, "id").getFirst();
    }
    String ids = "\"p\": \"" + SHARED + "\", \"c\": " + json(id);
    String access = call("query($p: ID!, $c: ID) { getConnectionSubjectAccess(projectId: $p, connectionId: $c) "
        + "{ subjectId } }", "{" + ids + "}");
    if (!values(access, "subjectId").contains(user)) {
        call("query($p: ID!, $c: ID!, $s: ID!) { addConnectionsAccess(projectId: $p, connectionIds: [$c], "
            + "subjects: [$s]) }", "{" + ids + ", \"s\": " + json(user) + "}");
    }
}

/**
 * Waits up to five minutes for the server to answer and to know its administrator, then opens a session as them.
 * CloudBeaver signs a new session in from the proxy's headers, which every call here sends: the administrator,
 * in team admin.
 */
void signInAsAdministrator() throws Exception {
    String admin = System.getenv("CB_ADMIN_NAME");
    for (int attempt = 1; ; attempt++) {
        try {
            call("mutation { openSession(defaultLocale: \"en\") { valid } }", "{}");
            String active = call("query { activeUser { userId teams { teamId } } }", "{}");
            if (!values(active, "userId").contains(admin) || !values(active, "teamId").contains("admin")) {
                throw new IllegalStateException("the session is not the administrator's: " + active);
            }
            return;
        } catch (IOException | IllegalStateException e) {
            if (attempt == 150) {
                throw e;
            }
            Thread.sleep(2000);
        }
    }
}

/** One GraphQL request in this session; returns the answer, or fails with it if it holds errors. */
String call(String query, String variables) throws IOException, InterruptedException {
    String body = "{\"query\": " + json(query) + ", \"variables\": " + variables + "}";
    HttpResponse<String> answer = HTTP.send(HttpRequest.newBuilder(API)
            .header("Content-Type", "application/json")
            .header(USER_HEADER, System.getenv("CB_ADMIN_NAME"))
            .header(TEAMS_HEADER, "admin")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString());
    if (answer.statusCode() != 200 || answer.body().contains("\"errors\"")) {
        throw new IllegalStateException(query + " answered " + answer.statusCode() + ": " + answer.body());
    }
    return answer.body();
}

/** A JSON string. The demo's names, passwords and tokens hold no control characters. */
static String json(String text) {
    return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
}

static List<String> values(String json, String field) {
    Matcher value = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
    List<String> values = new ArrayList<>();
    while (value.find()) {
        values.add(value.group(1));
    }
    return values;
}
