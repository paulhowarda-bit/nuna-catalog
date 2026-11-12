import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

/**
 * The run script's REST client: {@code Catalog <METHOD> <path> [json]} sends the request to
 * {@code http://fidice-catalog:8181/v1/engines/<path>} with the run's bearer token, prints the HTTP status and
 * writes the body to {@code /tmp/body}.
 */
public final class Catalog {

    public static void main(String[] args) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://fidice-catalog:8181/v1/engines/" + args[1]))
                .header("Authorization", "Bearer " + System.getenv("FIDICE_TOKEN"))
                .header("Content-Type", "application/json")
                .method(args[0], args.length > 2
                        ? HttpRequest.BodyPublishers.ofString(args[2])
                        : HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<Path> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofFile(Path.of("/tmp/body")));
        System.out.println(response.statusCode());
    }
}
