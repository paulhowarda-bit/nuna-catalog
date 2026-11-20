package nuna.catalog.store.mongo;

import static com.mongodb.MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL;
import static nuna.catalog.store.suite.Commits.advance;
import static nuna.catalog.store.suite.Commits.batch;
import static nuna.catalog.store.suite.Commits.newKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoConnectionPoolClearedException;
import com.mongodb.MongoException;
import com.mongodb.MongoNodeIsRecoveringException;
import com.mongodb.MongoNotPrimaryException;
import com.mongodb.MongoSocketOpenException;
import com.mongodb.MongoSocketReadException;
import com.mongodb.MongoSocketReadTimeoutException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.connection.ClusterId;
import com.mongodb.connection.ServerId;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.CommitBatch;
import nuna.catalog.store.PointerKey;
import nuna.catalog.store.StoreException;
import nuna.catalog.store.StoreUnavailableException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Which failures before commitTransaction mean MongoDB could not be reached or is changing primary (backlog 7):
 * those become {@link StoreUnavailableException}, which the REST layer answers 503 with Retry-After; the rest stay
 * plain store failures. The driver's exceptions are built as the driver builds them: a server's error from its
 * answer document, codes and labels included, and the driver's own labels added as it adds them. The last test
 * runs the store against a port on this machine where nothing listens. None needs a database.
 */
class UnavailableFailuresTest {

    private static final ServerAddress MEMBER = new ServerAddress("mongo-a", 27017);

    static Stream<Arguments> unavailable() {
        return Stream.of(
                Arguments.of("a refused connection", new MongoSocketOpenException("Exception opening socket", MEMBER,
                        new ConnectException("Connection refused"))),
                Arguments.of("a dropped connection", new MongoSocketReadException("Prematurely reached end of stream",
                        MEMBER)),
                Arguments.of("no answer within the socket timeout", new MongoSocketReadTimeoutException(
                        "Timeout while receiving message", MEMBER, new SocketTimeoutException("Read timed out"))),
                Arguments.of("no primary within the server selection timeout, in a transaction", inTransaction(
                        new MongoTimeoutException("Timed out while waiting for a server that matches"
                                + " ReadPreferenceServerSelector{readPreference=primary}"))),
                Arguments.of("no pooled connection within the wait (out of connections)", new MongoTimeoutException(
                        "Timed out after 120000 ms while waiting for a connection to server mongo-a:27017.")),
                Arguments.of("a pool cleared after a network error", new MongoConnectionPoolClearedException(
                        new ServerId(new ClusterId(), MEMBER), new MongoSocketReadException("Exception receiving message",
                        MEMBER))),
                Arguments.of("NotWritablePrimary in a transaction", new MongoNotPrimaryException(
                        answer(10107, "NotWritablePrimary", TRANSIENT_TRANSACTION_ERROR_LABEL), MEMBER)),
                Arguments.of("NotPrimaryNoSecondaryOk", new MongoNotPrimaryException(
                        answer(13435, "NotPrimaryNoSecondaryOk"), MEMBER)),
                Arguments.of("LegacyNotPrimary", new MongoNotPrimaryException(answer(10058, "LegacyNotPrimary"), MEMBER)),
                Arguments.of("InterruptedAtShutdown", new MongoNodeIsRecoveringException(
                        answer(11600, "InterruptedAtShutdown"), MEMBER)),
                Arguments.of("InterruptedDueToReplStateChange", new MongoNodeIsRecoveringException(
                        answer(11602, "InterruptedDueToReplStateChange"), MEMBER)),
                Arguments.of("NotPrimaryOrSecondary", new MongoNodeIsRecoveringException(
                        answer(13436, "NotPrimaryOrSecondary"), MEMBER)),
                Arguments.of("PrimarySteppedDown", new MongoNodeIsRecoveringException(
                        answer(189, "PrimarySteppedDown"), MEMBER)),
                Arguments.of("ShutdownInProgress", new MongoNodeIsRecoveringException(
                        answer(91, "ShutdownInProgress"), MEMBER)),
                Arguments.of("HostUnreachable", command(6, "HostUnreachable")),
                Arguments.of("HostNotFound", command(7, "HostNotFound")),
                Arguments.of("NetworkTimeout", command(89, "NetworkTimeout")),
                Arguments.of("SocketException", command(9001, "SocketException")),
                Arguments.of("ReadConcernMajorityNotAvailableYet", command(134, "ReadConcernMajorityNotAvailableYet")),
                Arguments.of("ExceededTimeLimit", command(262, "ExceededTimeLimit")),
                Arguments.of("NoSuchTransaction on a new primary", command(251, "NoSuchTransaction",
                        TRANSIENT_TRANSACTION_ERROR_LABEL)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void unavailable(String what, MongoException failure) {
        assertThat(Transactions.failed("x failed", failure)).isInstanceOf(StoreUnavailableException.class);
    }

    static Stream<Arguments> answered() {
        return Stream.of(
                Arguments.of("a write conflict, though labelled TransientTransactionError", command(112, "WriteConflict",
                        TRANSIENT_TRANSACTION_ERROR_LABEL)),
                Arguments.of("NoSuchTransaction without the label", command(251, "NoSuchTransaction")),
                Arguments.of("a duplicate key", command(11000, "DuplicateKey")),
                Arguments.of("an operation over its time limit", command(50, "MaxTimeMSExpired")),
                Arguments.of("a role without the privilege", command(13, "Unauthorized")),
                Arguments.of("a wrong password: a misconfiguration, not an outage", command(18, "AuthenticationFailed")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void answered(String what, MongoException failure) {
        assertThat(Transactions.failed("x failed", failure)).isExactlyInstanceOf(StoreException.class);
    }

    @Test
    void aDatabaseThatCannotBeReachedIsUnavailableToReadsCommitsAndLookups() throws IOException {
        int port;
        try (ServerSocket closed = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = closed.getLocalPort();
        }
        try (MongoClient client = MongoClients.create(MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString("mongodb://127.0.0.1:" + port + "/?replicaSet=rs0"))
                .applyToClusterSettings(cluster -> cluster.serverSelectionTimeout(500, TimeUnit.MILLISECONDS))
                .applyToSocketSettings(socket -> socket.connectTimeout(1, TimeUnit.SECONDS))
                .build())) {
            CatalogStore store = new MongoCatalogStore(client, "catalog");
            PointerKey key = newKey();
            CommitBatch batch = batch(advance(key, Optional.empty(), "never"));

            assertThatThrownBy(() -> store.readPointer(key)).isInstanceOf(StoreUnavailableException.class)
                    .hasCauseInstanceOf(MongoTimeoutException.class);
            // In a transaction the driver labels the timeout: the transaction will not commit.
            assertThatThrownBy(() -> store.commit(batch)).isInstanceOf(StoreUnavailableException.class)
                    .cause().isInstanceOfSatisfying(MongoTimeoutException.class,
                            timeout -> assertThat(timeout.getErrorLabels()).contains(TRANSIENT_TRANSACTION_ERROR_LABEL));
            assertThatThrownBy(() -> store.findCommit(batch.commitId(), key)).isInstanceOf(StoreUnavailableException.class)
                    .hasCauseInstanceOf(MongoTimeoutException.class);
        }
    }

    /** A server's error answer, as the server sends it. */
    private static BsonDocument answer(int code, String codeName, String... labels) {
        BsonArray errorLabels = new BsonArray();
        for (String label : labels) {
            errorLabels.add(new BsonString(label));
        }
        return new BsonDocument("ok", new BsonDouble(0)).append("errmsg", new BsonString(codeName))
                .append("code", new BsonInt32(code)).append("codeName", new BsonString(codeName))
                .append("errorLabels", errorLabels);
    }

    private static MongoCommandException command(int code, String codeName, String... labels) {
        return new MongoCommandException(answer(code, codeName, labels), MEMBER);
    }

    /** As the driver labels a network error or timeout in a transaction. */
    private static MongoException inTransaction(MongoException failure) {
        failure.addLabel(TRANSIENT_TRANSACTION_ERROR_LABEL);
        return failure;
    }
}
