package nuna.catalog.server;

/** The server's {@link ErrorResponses}, for tests outside its package that serve the routes without Spring Boot. */
public final class ErrorResponsesForTests {

    private ErrorResponsesForTests() {
    }

    /** The controller advice that turns a route's thrown failure into its Iceberg error response. */
    public static Object advice() {
        return new ErrorResponses();
    }
}
