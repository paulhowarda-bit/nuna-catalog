package keel.catalog.core.commit;

import static keel.catalog.core.commit.Harness.ALICE;
import static keel.catalog.core.commit.Requests.assertUuid;
import static keel.catalog.core.commit.Requests.setProperties;
import static keel.catalog.core.commit.Requests.update;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import keel.catalog.core.Names;
import keel.catalog.core.commit.CommitReply.Failure;
import keel.catalog.core.commit.CommitReply.How;
import keel.catalog.core.commit.CommitReply.Success;
import keel.catalog.store.CatalogObject;
import keel.catalog.store.Change;
import keel.catalog.store.CommitOutcomeUnknownException;
import keel.catalog.store.GovernanceAttribute;
import keel.catalog.store.Name;
import keel.catalog.store.NamespaceRecord;
import keel.catalog.store.Pointer;
import keel.catalog.store.WriteResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Unregister, register-view, and registering a dropped or unregistered object's own file again (D13). */
class RegisteringTest {

    private final Harness h = new Harness();
    private final List<CatalogObject> undropsAsked = new ArrayList<>();
    private final CommitEngine.Undrop asked = undropsAsked::add;

    /** As the REST layer asks: for the table it finds by that name, which it has authorized. */
    private CommitReply unregister(String table) {
        return h.engine().unregisterTable(h.name(table), found(table), ALICE, Optional.empty());
    }

    private Optional<UUID> found(String table) {
        return h.faults.real.findObject(h.namespaceId, Names.normalize(table)).map(CatalogObject::id);
    }

    private CommitReply registerTable(String name, String location) {
        return h.engine().registerTable(h.name(name), register(name, location), ALICE, Optional.empty(), asked);
    }

    private CommitReply registerView(String name, String location) {
        return h.engine().registerView(h.name(name), register(name, location), ALICE, Optional.empty(), asked);
    }

    private static String register(String name, String location) {
        return "{\"name\":\"" + name + "\",\"metadata-location\":\"" + location + "\"}";
    }

    private CatalogObject byId(UUID id) {
        return h.faults.real.readObject(id).orElseThrow();
    }

    private static void failure(CommitReply reply, int status, String type) {
        assertThat(reply).isInstanceOfSatisfying(Failure.class, f -> {
            assertThat(f.status()).as(f.message()).isEqualTo(status);
            assertThat(f.type()).as(f.message()).isEqualTo(type);
        });
    }

    @Nested
    class Unregistering {

        @Test
        @DisplayName("an unregister answers the current metadata, frees the name and refuses every later commit")
        void unregistered() {
            Success created = h.createTable("Orders");
            CatalogObject orders = h.object("Orders");

            CommitReply reply = unregister("Orders");

            assertThat(reply).isEqualTo(new Success(orders.id(), created.metadataLocation(), created.metadataSha256(),
                    created.metadataJson(), How.COMMITTED));
            CatalogObject after = byId(orders.id());
            assertThat(after.state()).isEqualTo(CatalogObject.State.UNREGISTERED);
            assertThat(after.purgeAfter()).isEmpty();
            assertThat(h.faults.real.findObject(h.namespaceId, "orders")).isEmpty();
            assertThat(h.faults.real.readPointer(orders.mainKey()).map(Pointer::metadataLocation))
                    .contains(created.metadataLocation());
            CommitEvents.Commit event = h.events.getLast();
            assertThat(event.kind()).isEqualTo(CommitEvents.Kind.UNREGISTER);
            assertThat(event.before()).contains(event.after());
            failure(h.engine().commitTable(h.name("Orders"),
                    update(assertUuid(orders.id().toString()), setProperties(Map.of("a", "1"))), ALICE,
                    Optional.empty()), 404, "NoSuchTableException");
        }

        @Test
        @DisplayName("a table under a legal hold, on it or above it, is 403 and stays active")
        void held() {
            h.createTable("Orders");
            h.hold(h.namespace());

            failure(unregister("Orders"), 403, "ForbiddenException");
            assertThat(h.object("Orders").state()).isEqualTo(CatalogObject.State.ACTIVE);
        }

        @Test
        @DisplayName("a table under a DR freeze is 403 and stays active")
        void frozen() {
            h.createTable("Orders");
            h.attribute(h.namespace(), GovernanceAttribute.Kind.DR_FREEZE, "reconcile");

            failure(unregister("Orders"), 403, "ForbiddenException");
            assertThat(h.object("Orders").state()).isEqualTo(CatalogObject.State.ACTIVE);
        }

        @Test
        @DisplayName("a missing table, or a view of that name, is 404 NoSuchTable")
        void missing() {
            h.createView("Daily");

            failure(unregister("Orders"), 404, "NoSuchTableException");
            failure(unregister("Daily"), 404, "NoSuchTableException");
            assertThat(h.object("Daily").state()).isEqualTo(CatalogObject.State.ACTIVE);
        }

        @Test
        @DisplayName("a commit that lands first is not lost: the unregister reads again and answers its metadata")
        void commitLandsFirst() {
            h.createTable("Orders");
            CatalogObject orders = h.object("Orders");
            h.faults.once("unregisterObject", (args, proceed) -> {
                assertThat(h.engine(h.faults.real).commitTable(h.name("Orders"),
                        update(assertUuid(orders.id().toString()), setProperties(Map.of("a", "1"))), ALICE,
                        Optional.empty())).isInstanceOf(Success.class);
                return proceed.call();
            });

            CommitReply reply = unregister("Orders");

            Pointer current = h.faults.real.readPointer(orders.mainKey()).orElseThrow();
            assertThat(current.version()).isEqualTo(2);
            assertThat(reply).isInstanceOfSatisfying(Success.class,
                    s -> assertThat(s.metadataLocation()).isEqualTo(current.metadataLocation()));
            assertThat(h.faults.calls("unregisterObject")).isEqualTo(2);
            assertThat(byId(orders.id()).state()).isEqualTo(CatalogObject.State.UNREGISTERED);
        }

        @Test
        @DisplayName("an unregister whose outcome was lost but which landed is a success")
        void unknownButLanded() {
            Success created = h.createTable("Orders");
            h.faults.once("unregisterObject", (args, proceed) -> {
                proceed.call();
                throw new CommitOutcomeUnknownException(((Change) args[2]).id(), null);
            });

            assertThat(unregister("Orders")).isInstanceOfSatisfying(Success.class,
                    s -> assertThat(s.metadataLocation()).isEqualTo(created.metadataLocation()));
            assertThat(byId(created.objectId()).state()).isEqualTo(CatalogObject.State.UNREGISTERED);
        }

        @Test
        @DisplayName("a retried unregister is replayed from its key, though the table is gone (D10)")
        void replayed() {
            Success created = h.createTable("Orders");
            String key = "key-" + UUID.randomUUID();

            CommitReply first = h.engine().unregisterTable(h.name("Orders"), found("Orders"), ALICE, Optional.of(key));
            CommitReply again = h.engine().unregisterTable(h.name("Orders"), found("Orders"), ALICE, Optional.of(key));

            assertThat(first).isInstanceOf(Success.class);
            assertThat(again).isEqualTo(new Success(created.objectId(), created.metadataLocation(),
                    created.metadataSha256(), created.metadataJson(), How.REPLAYED));
            failure(unregister("Orders"), 404, "NoSuchTableException");
        }

        @Test
        @DisplayName("a table that takes the name after the REST layer checked it, or none, is 404 and stays active")
        void onlyTheAuthorizedTable() {
            Success first = h.createTable("Orders");
            assertThat(h.faults.real.dropObject(first.objectId(), h.clock.instant().plus(Duration.ofDays(7)),
                    Harness.change())).isEqualTo(WriteResult.APPLIED);
            Success second = h.createTable("Orders");

            failure(h.engine().unregisterTable(h.name("Orders"), Optional.of(first.objectId()), ALICE,
                    Optional.empty()), 404, "NoSuchTableException");
            failure(h.engine().unregisterTable(h.name("Orders"), Optional.empty(), ALICE, Optional.empty()), 404,
                    "NoSuchTableException");

            assertThat(byId(second.objectId()).state()).isEqualTo(CatalogObject.State.ACTIVE);
            assertThat(byId(first.objectId()).state()).isEqualTo(CatalogObject.State.DROPPED);
            assertThat(h.faults.calls("unregisterObject")).isZero();
        }

        @Test
        @DisplayName("a table regulated, or flagged for row/column policy, after the REST layer checked it is 403 and stays active")
        void flaggedMeanwhile() {
            h.createTable("Orders");
            CatalogObject orders = h.object("Orders");
            h.regulate(h.namespace());

            failure(unregister("Orders"), 403, "ForbiddenException");
            h.attribute(h.namespace(), GovernanceAttribute.Kind.REGULATED, "false");
            h.attribute(orders.securable(), GovernanceAttribute.Kind.ROW_COLUMN_POLICY, "true");
            failure(unregister("Orders"), 403, "ForbiddenException");
            assertThat(h.object("Orders").state()).isEqualTo(CatalogObject.State.ACTIVE);
        }
    }

    @Nested
    class Reviving {

        @Test
        @DisplayName("an unregistered table's own file brings it back with its id, as the next pointer version")
        void unregisteredComesBack() {
            Success created = h.createTable("Orders");
            assertThat(unregister("Orders")).isInstanceOf(Success.class);

            CommitReply reply = registerTable("Orders2", created.metadataLocation());

            assertThat(reply).isInstanceOfSatisfying(Success.class, s -> {
                assertThat(s.objectId()).isEqualTo(created.objectId());
                assertThat(s.metadataLocation()).isEqualTo(created.metadataLocation());
            });
            CatalogObject back = byId(created.objectId());
            assertThat(back.state()).isEqualTo(CatalogObject.State.ACTIVE);
            assertThat(back.name().display()).isEqualTo("Orders2");
            assertThat(back.droppedAt()).isEmpty();
            assertThat(h.pointer("Orders2").version()).isEqualTo(2);
            assertThat(h.events.getLast().kind()).isEqualTo(CommitEvents.Kind.COMMIT);
            assertThat(undropsAsked).isEmpty();
        }

        @Test
        @DisplayName("a dropped table's own file is an undrop: the caller is asked, and it is reported as one")
        void droppedComesBackAsAnUndrop() {
            Success created = h.createTable("Orders");
            assertThat(h.faults.real.dropObject(created.objectId(), h.clock.instant().plus(Duration.ofDays(7)),
                    Harness.change())).isEqualTo(WriteResult.APPLIED);

            CommitReply reply = registerTable("Orders", created.metadataLocation());

            assertThat(reply).isInstanceOf(Success.class);
            assertThat(undropsAsked).extracting(CatalogObject::id).containsExactly(created.objectId());
            assertThat(byId(created.objectId()).state()).isEqualTo(CatalogObject.State.ACTIVE);
            assertThat(h.events.getLast().kind()).isEqualTo(CommitEvents.Kind.UNDROP);
        }

        @Test
        @DisplayName("a caller who may not undrop the dropped table is refused, and nothing changes")
        void undropRefused() {
            Success created = h.createTable("Orders");
            assertThat(h.faults.real.dropObject(created.objectId(), h.clock.instant().plus(Duration.ofDays(7)),
                    Harness.change())).isEqualTo(WriteResult.APPLIED);
            CommitEngine.Undrop refuse = dropped -> {
                throw new IllegalStateException("no create on the namespace");
            };

            assertThatThrownBy(() -> h.engine().registerTable(h.name("Orders"),
                    register("Orders", created.metadataLocation()), ALICE, Optional.empty(), refuse))
                    .hasMessage("no create on the namespace");
            assertThat(byId(created.objectId()).state()).isEqualTo(CatalogObject.State.DROPPED);
        }

        @Test
        @DisplayName("a dropped table whose undrop window has closed is 409 UndropWindowClosed")
        void windowClosed() {
            Success created = h.createTable("Orders");
            assertThat(h.faults.real.dropObject(created.objectId(), h.clock.instant().plus(Duration.ofMinutes(1)),
                    Harness.change())).isEqualTo(WriteResult.APPLIED);
            h.clock.advance(Duration.ofMinutes(2));

            failure(registerTable("Orders", created.metadataLocation()), 409, "UndropWindowClosedException");
            assertThat(undropsAsked).isEmpty();
            assertThat(byId(created.objectId()).state()).isEqualTo(CatalogObject.State.DROPPED);
        }

        @Test
        @DisplayName("a dropped or unregistered table is brought back only in its own namespace; elsewhere is 409")
        void onlyInItsOwnNamespace() {
            UUID hr = UUID.randomUUID();
            assertThat(h.faults.real.createNamespace(new NamespaceRecord(h.catalogId, hr, h.catalogId,
                    new Name("hr", "HR"), Map.of(), 1), Harness.change())).isEqualTo(WriteResult.APPLIED);
            Success dropped = h.createTable("Orders");
            assertThat(h.faults.real.dropObject(dropped.objectId(), h.clock.instant().plus(Duration.ofDays(7)),
                    Harness.change())).isEqualTo(WriteResult.APPLIED);
            Success unregistered = h.createTable("Ledger");
            assertThat(unregister("Ledger")).isInstanceOf(Success.class);

            for (Success table : List.of(dropped, unregistered)) {
                failure(h.engine().registerTable(new ObjectName(h.catalogId, hr, new Name("moved", "Moved")),
                        register("Moved", table.metadataLocation()), ALICE, Optional.empty(), asked), 409,
                        "AlreadyExistsException");
            }

            assertThat(undropsAsked).isEmpty();
            assertThat(byId(dropped.objectId()).state()).isEqualTo(CatalogObject.State.DROPPED);
            assertThat(byId(unregistered.objectId()).state()).isEqualTo(CatalogObject.State.UNREGISTERED);
            assertThat(h.faults.real.findObject(hr, "moved")).isEmpty();
        }

        @Test
        @DisplayName("only the file a table left at brings it back: an older one is 409, a changed one 500, and it stays gone")
        void onlyTheFileItLeftAt() {
            Success created = h.createTable("Orders");
            CatalogObject orders = h.object("Orders");
            assertThat(h.engine().commitTable(h.name("Orders"),
                    update(assertUuid(orders.id().toString()), setProperties(Map.of("a", "1"))), ALICE,
                    Optional.empty())).isInstanceOf(Success.class);
            CommitReply left = unregister("Orders");
            assertThat(left).isInstanceOf(Success.class);

            failure(registerTable("Orders", created.metadataLocation()), 409, "AlreadyExistsException");
            Success at = (Success) left;
            h.files.tamper(at.metadataLocation(), at.metadataJson() + " ");
            assertThat(registerTable("Orders", at.metadataLocation())).isInstanceOf(CommitReply.ServerError.class);

            assertThat(h.alerts).containsExactly("integrity " + orders.id() + " " + at.metadataLocation());
            assertThat(byId(orders.id()).state()).isEqualTo(CatalogObject.State.UNREGISTERED);
        }

        @Test
        @DisplayName("an active table's file registered under another name is 409: the UUID is taken")
        void activeUuidRefused() {
            Success created = h.createTable("Orders");

            failure(registerTable("Copy", created.metadataLocation()), 409, "AlreadyExistsException");
            assertThat(h.faults.real.findObject(h.namespaceId, "copy")).isEmpty();
        }

        @Test
        @DisplayName("an unregistered table brought back where another object holds the name is 409, and stays unregistered")
        void nameTaken() {
            Success created = h.createTable("Orders");
            assertThat(unregister("Orders")).isInstanceOf(Success.class);
            h.createTable("Orders");

            failure(registerTable("Orders", created.metadataLocation()), 409, "AlreadyExistsException");
            assertThat(byId(created.objectId()).state()).isEqualTo(CatalogObject.State.UNREGISTERED);
        }
    }

    @Nested
    class RegisteringViews {

        /** A copy of {@code view}'s metadata under a fresh UUID, at the location the catalog assigns that UUID. */
        private String freshCopy(Success view) {
            UUID id = UUID.randomUUID();
            String json = view.metadataJson().replace(view.objectId().toString(), id.toString());
            return h.files.write(h.files.newLocation(id), 1, json).location();
        }

        @Test
        @DisplayName("a view's existing metadata file is registered under its UUID, at its assigned location")
        void registered() {
            Success daily = h.createView("Daily");
            String location = freshCopy(daily);

            CommitReply reply = registerView("Weekly", location);

            assertThat(reply).isInstanceOfSatisfying(Success.class, s -> {
                assertThat(s.metadataLocation()).isEqualTo(location);
                assertThat(location).startsWith(h.files.newLocation(s.objectId()) + "/");
            });
            CatalogObject weekly = h.object("Weekly");
            assertThat(weekly.kind()).isEqualTo(CatalogObject.Kind.VIEW);
            assertThat(h.pointer("Weekly").metadataLocation()).isEqualTo(location);
        }

        @Test
        @DisplayName("a view file whose UUID another view holds is 409, and nothing is created")
        void uuidTaken() {
            Success daily = h.createView("Daily");

            failure(registerView("Weekly", daily.metadataLocation()), 409, "AlreadyExistsException");
            assertThat(h.faults.real.findObject(h.namespaceId, "weekly")).isEmpty();
        }

        @Test
        @DisplayName("a name already held is 409 before the file is read")
        void nameTaken() {
            h.createView("Daily");
            int reads = h.files.reads.get();

            failure(registerView("Daily", "s3://lake/warehouse/none/metadata/00001-x.metadata.json"), 409,
                    "AlreadyExistsException");
            assertThat(h.files.reads.get()).isEqualTo(reads);
        }

        @Test
        @DisplayName("an unregistered table's file is not a view: registering it as one is 400")
        void tableFileIsNotAView() {
            Success created = h.createTable("Orders");

            assertThat(registerView("Weekly", created.metadataLocation())).isInstanceOfSatisfying(Failure.class,
                    f -> assertThat(f.status()).isEqualTo(400));
        }
    }

    @Test
    @DisplayName("an object's labels are the nearest level's classification, owner and retention class, and nothing else")
    void labels() {
        h.createTable("Orders");
        CatalogObject orders = h.object("Orders");
        h.attribute(h.namespace(), GovernanceAttribute.Kind.CLASSIFICATION, "internal");
        h.attribute(orders.securable(), GovernanceAttribute.Kind.CLASSIFICATION, "confidential");
        h.attribute(h.namespace(), GovernanceAttribute.Kind.OWNER, "sales-team");
        h.regulate(h.namespace());
        h.attribute(orders.securable(), GovernanceAttribute.Kind.ROW_COLUMN_POLICY, "true");

        assertThat(GovernanceChain.of(h.faults.real, orders).labels())
                .isEqualTo(Map.of("classification", "confidential", "owner", "sales-team"));
    }
}
