package fidice.catalog.store.sql;

import fidice.catalog.store.CatalogRecord;
import fidice.catalog.store.CatalogStore;
import fidice.catalog.store.StoreException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** PostgreSQL-wire implementation: PostgreSQL, CockroachDB and YugabyteDB (D7). */
public final class SqlCatalogStore implements CatalogStore {

    private final DataSource dataSource;

    public SqlCatalogStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Optional<CatalogRecord> findCatalog(String normalizedName) {
        String sql = "SELECT id, name_norm, name FROM catalog WHERE name_norm = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalizedName);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(new CatalogRecord(
                        row.getObject(1, UUID.class), row.getString(2), row.getString(3)));
            }
        } catch (SQLException e) {
            throw new StoreException("catalog lookup failed", e);
        }
    }
}
