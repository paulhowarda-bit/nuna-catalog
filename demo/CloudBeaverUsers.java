/*
 * The demo's people in CloudBeaver. The pod's postStart hook (demo/k8s/cloudbeaver.yaml) runs this as the server
 * starts, because CloudBeaver keeps its users inside the pod and every new pod starts without them. It signs in
 * as the administrator (CB_ADMIN_NAME, CB_ADMIN_PASSWORD), adds each user in DEMO_USERS ("name:password ..."),
 * and grants every connection to team "user", which new users join. Each step can run again.
 *
 * CloudBeaver's local sign-in takes a password the way its page sends one: MD5, in uppercase hex. The image
 * compiles this when it is built (demo/k8s/cloudbeaver.Dockerfile).
 */
import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

static final URI API = URI.create("http://localhost:8978/api/gql");
static final HttpClient HTTP = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();

void main() throws Exception {
    signInAsAdministrator();
    String existing = call("query { listUsers(page: {offset: 0, limit: 1000}, filter: {}) { userId } }", "{}");
    for (String entry : System.getenv("DEMO_USERS").trim().split("\\s+")) {
        String user = entry.substring(0, entry.indexOf(':'));
        String password = entry.substring(entry.indexOf(':') + 1);
        if (!values(existing, "userId").contains(user)) {
            call("query($u: ID!) { createUser(userId: $u, enabled: true) { userId } }", "{\"u\": \"" + user + "\"}");
        }
        call("query($u: ID!, $c: Object!) { setUserCredentials(userId: $u, providerId: \"local\", credentials: $c) }",
            "{\"u\": \"" + user + "\", \"c\": {\"password\": \"" + md5(password) + "\"}}");
    }
    String connections = call("query { userConnections { id projectId } }", "{}");
    Matcher connection = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\",\\s*\"projectId\"\\s*:\\s*\"([^\"]+)\"")
        .matcher(connections);
    while (connection.find()) {
        String ids = "{\"p\": \"" + connection.group(2) + "\", \"c\": \"" + connection.group(1) + "\"}";
        String access = call("query($p: ID!, $c: ID) { getConnectionSubjectAccess(projectId: $p, connectionId: $c) "
            + "{ subjectId } }", ids);
        if (!values(access, "subjectId").contains("user")) {
            call("query($p: ID!, $c: ID!) { addConnectionsAccess(projectId: $p, connectionIds: [$c], "
                + "subjects: [\"user\"]) }", ids);
        }
    }
}

/** Waits up to five minutes for the server to answer and to know its administrator, then signs in. */
void signInAsAdministrator() throws Exception {
    String query = "query($c: Object) { authLogin(provider: \"local\", credentials: $c) { authStatus } }";
    String variables = "{\"c\": {\"user\": \"" + System.getenv("CB_ADMIN_NAME") + "\", \"password\": \""
        + md5(System.getenv("CB_ADMIN_PASSWORD")) + "\"}}";
    for (int attempt = 1; ; attempt++) {
        try {
            call(query, variables);
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
    String body = "{\"query\": \"" + query.replace("\"", "\\\"") + "\", \"variables\": " + variables + "}";
    HttpResponse<String> answer = HTTP.send(HttpRequest.newBuilder(API)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString());
    if (answer.statusCode() != 200 || answer.body().contains("\"errors\"")) {
        throw new IllegalStateException(query + " answered " + answer.statusCode() + ": " + answer.body());
    }
    return answer.body();
}

static List<String> values(String json, String field) {
    Matcher value = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
    List<String> values = new ArrayList<>();
    while (value.find()) {
        values.add(value.group(1));
    }
    return values;
}

static String md5(String password) throws Exception {
    return HexFormat.of().withUpperCase()
        .formatHex(MessageDigest.getInstance("MD5").digest(password.getBytes(StandardCharsets.UTF_8)));
}
