package nuna.catalog.core;

/**
 * A request failure carried to the REST layer as an Iceberg error response: a definite 4xx, or a
 * 500 the server reports as such. {@code type} is the Iceberg error class name clients map to an
 * exception.
 */
public final class CatalogError extends RuntimeException {

    private final int status;
    private final String type;

    private CatalogError(int status, String type, String message) {
        super(message);
        this.status = status;
        this.type = type;
    }

    /** Any failure; {@code type} is an Iceberg error class name. */
    public static CatalogError of(int status, String type, String message) {
        return new CatalogError(status, type, message);
    }

    public static CatalogError badRequest(String message) {
        return new CatalogError(400, "BadRequestException", message);
    }

    public static CatalogError noSuchWarehouse(String message) {
        return new CatalogError(404, "NoSuchWarehouseException", message);
    }

    public int status() {
        return status;
    }

    public String type() {
        return type;
    }
}
