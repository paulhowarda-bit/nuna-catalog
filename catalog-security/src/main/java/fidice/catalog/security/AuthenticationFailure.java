package fidice.catalog.security;

/**
 * A request that cannot be accepted as coming from anyone. {@code status} is 401 when the token is
 * not acceptable, and 403 when the token is valid but its client may not act for another user.
 */
public final class AuthenticationFailure extends RuntimeException {

    private final int status;

    private AuthenticationFailure(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    static AuthenticationFailure invalidToken(String message) {
        return new AuthenticationFailure(401, message, null);
    }

    static AuthenticationFailure invalidToken(String message, Throwable cause) {
        return new AuthenticationFailure(401, message, cause);
    }

    static AuthenticationFailure assertionRefused(String message) {
        return new AuthenticationFailure(403, message, null);
    }

    public int status() {
        return status;
    }
}
