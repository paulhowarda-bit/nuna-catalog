package keel.catalog.security;

import static org.assertj.core.api.Assertions.assertThat;

import keel.catalog.core.principal.Caller;
import keel.catalog.core.principal.Principal;
import keel.catalog.security.Authorizer.Decision;
import keel.catalog.security.Authorizer.Operation;
import keel.catalog.security.Authorizer.Reason;
import keel.catalog.security.Authorizer.Request;
import keel.catalog.security.Authorizer.Target;
import keel.catalog.store.CatalogObject;
import keel.catalog.store.GovernanceAttribute;
import keel.catalog.store.NamespaceRecord;
import keel.catalog.store.Securable;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** RBAC (D18, D19) and the governance rules (D17, D20, D27), decided against grants stored in PostgreSQL. */
class AuthorizerTest {

    private static final Operation LIST = new Operation("listTables", Privilege.DESCRIBE, false);
    private static final Operation LOAD = new Operation("loadTable", Privilege.SELECT, true);
    private static final Operation APPEND = new Operation("commitTable", Privilege.WRITE, true);
    private static final Operation ALTER = new Operation("commitTable", Privilege.MODIFY, true);
    private static final Operation DROP = new Operation("dropTable", Privilege.MODIFY, false);
    private static final Operation CREATE = new Operation("createTable", Privilege.CREATE, false);
    private static final Operation GRANT = new Operation("grant", Privilege.MANAGE_GRANTS, false);
    private static final Operation TAG = new Operation("classify", Privilege.MANAGE_TAGS, false);

    private final Estate estate = new Estate();
    private final Separation separation = new Separation(Set.of("catalog-grant-admins"), Set.of("platform-ops"));
    private final Authorizer authorizer = authorizer(false);

    private Authorizer authorizer(boolean refuseIdentityLessWrites) {
        return new Authorizer(estate.store, estate.store, estate.store, separation, refuseIdentityLessWrites);
    }

    @Test
    void eachRungOfTheLadderGivesTheRungsBelowAndNothingBeside() {
        NamespaceRecord sales = estate.namespace("Sales");
        CatalogObject orders = estate.table(sales, "orders");
        estate.grant(orders, "readers", Privilege.SELECT);
        estate.grant(orders, "writers", Privilege.WRITE);
        estate.grant(orders, "owners", Privilege.MODIFY);

        assertAllowed(person("r", "readers"), orders, LIST, LOAD);
        assertDenied(person("r", "readers"), orders, Privilege.WRITE, APPEND, ALTER, DROP, GRANT, TAG);
        assertAllowed(person("w", "writers"), orders, LIST, LOAD, APPEND);
        assertDenied(person("w", "writers"), orders, Privilege.MODIFY, ALTER, DROP);
        assertAllowed(person("o", "owners"), orders, LIST, LOAD, APPEND, ALTER, DROP);
        assertDenied(person("o", "owners"), orders, Privilege.MANAGE_GRANTS, GRANT);
        assertDenied(person("o", "owners"), orders, Privilege.MANAGE_TAGS, TAG);
        assertThat(authorizer.decide(person("o", "owners"), Target.of(sales), CREATE).allowed()).isFalse();
    }

    @Test
    void grantsInheritDownwardIncludingToObjectsCreatedLater() {
        NamespaceRecord trading = estate.namespace("Trading");
        estate.grant(trading, "traders", Privilege.SELECT);
        estate.grantOnCatalog("analysts", Privilege.DESCRIBE);
        NamespaceRecord desk = estate.namespace(trading, "Desk");
        NamespaceRecord book = estate.namespace(desk, "Book");
        CatalogObject later = estate.table(book, "fills");
        NamespaceRecord other = estate.namespace("Other");
        CatalogObject elsewhere = estate.table(other, "fills");

        assertAllowed(person("t", "traders"), later, LIST, LOAD);
        assertDenied(person("t", "traders"), elsewhere, Privilege.DESCRIBE, LIST);
        assertAllowed(person("a", "analysts"), elsewhere, LIST);
        assertDenied(person("a", "analysts"), elsewhere, Privilege.SELECT, LOAD);
        assertThat(authorizer.privileges(Set.of("traders"), Target.of(later))).containsExactly(Privilege.SELECT);
    }

    @Test
    void aGrantOnAChildDoesNotReachUpOrAcross() {
        NamespaceRecord parent = estate.namespace("Parent");
        NamespaceRecord child = estate.namespace(parent, "Child");
        CatalogObject table = estate.table(child, "t");
        CatalogObject sibling = estate.table(parent, "s");
        estate.grant(table, "narrow", Privilege.MODIFY);

        assertAllowed(person("n", "narrow"), table, LOAD, DROP);
        assertDenied(person("n", "narrow"), sibling, Privilege.DESCRIBE, LIST);
        assertThat(authorizer.decide(person("n", "narrow"), Target.of(child), LIST).allowed()).isFalse();
        assertThat(authorizer.decide(person("n", "narrow"), estate.catalog(), LIST).allowed()).isFalse();
    }

    @Test
    void aBatchCheckGivesEachTargetOnlyTheGrantsOnItsOwnChain() {
        NamespaceRecord namespace = estate.namespace("Batch");
        CatalogObject granted = estate.table(namespace, "granted");
        CatalogObject sibling = estate.table(namespace, "sibling");
        estate.grant(granted, "narrow", Privilege.MODIFY);

        List<Decision> decisions = authorizer.decide(person("n", "narrow"),
                List.of(new Request(Target.of(granted), LOAD), new Request(Target.of(sibling), LOAD)));

        assertThat(decisions.get(0)).isInstanceOf(Decision.Allowed.class);
        assertThat(decisions.get(1)).as("the grant on the other table in the batch").isInstanceOf(Decision.Denied.class);
    }

    @Test
    void groupsAreMatchedExactlyAndNoGroupsMeansNothing() {
        CatalogObject table = estate.table(estate.namespace("Exact"), "t");
        estate.grant(table, "Readers", Privilege.SELECT);

        assertDenied(person("x", "readers"), table, Privilege.SELECT, LOAD);
        assertDenied(person("x"), table, Privilege.DESCRIBE, LIST);
        assertAllowed(person("x", "Readers"), table, LOAD);
    }

    @Test
    void thereIsNoSuperuserNotEvenTheDeploymentsGrantAdmins() {
        CatalogObject table = estate.table(estate.namespace("Crown"), "jewels");

        Caller admin = person("ga", "catalog-grant-admins");
        assertAllowed(admin, table, LIST, GRANT);
        assertDenied(admin, table, Privilege.SELECT, LOAD);
        assertDenied(admin, table, Privilege.MODIFY, DROP);
        assertDenied(person("op", "platform-ops"), table, Privilege.DESCRIBE, LIST);
        // Every privilege of D18, each held by a different group, still gives one person only what their groups hold.
        for (Privilege privilege : Privilege.values()) {
            estate.grantOnCatalog("g-" + privilege.wireName(), privilege);
        }
        assertDenied(person("p", "g-modify"), table, Privilege.MANAGE_GRANTS, GRANT);
    }

    @Test
    void aTrustedEngineIsAuthorizedAsTheEndUserItNames() {
        CatalogObject table = estate.table(estate.namespace("Engines"), "t");
        estate.grant(table, "trino-service", Privilege.MODIFY);
        estate.grant(table, "alice-team", Privilege.SELECT);
        Principal alice = new Principal("alice", Set.of("alice-team"));

        Caller forAlice = new Caller(new Principal("svc-trino", Set.of("trino-service")), "trino", true,
                Optional.of(alice));
        assertAllowed(forAlice, table, LOAD);
        assertDenied(forAlice, table, Privilege.WRITE, APPEND);
        Caller asItself = new Caller(new Principal("svc-trino", Set.of("trino-service")), "trino", true,
                Optional.empty());
        assertAllowed(asItself, table, APPEND, DROP);
    }

    @Test
    void anUnclassifiedTableInARegulatedNamespaceIsClosedButStillVisible() {
        NamespaceRecord regulated = estate.namespace("Regulated");
        estate.regulated(regulated);
        NamespaceRecord nested = estate.namespace(regulated, "Nested");
        CatalogObject table = estate.table(nested, "positions");
        CatalogObject view = estate.view(nested, "positions_v");
        estate.grant(regulated, "desk", Privilege.MODIFY);

        assertAllowed(person("d", "desk"), table, LIST);
        assertReason(person("d", "desk"), table, LOAD, Reason.UNCLASSIFIED);
        assertReason(person("d", "desk"), table, APPEND, Reason.UNCLASSIFIED);
        assertReason(person("d", "desk"), table, DROP, Reason.UNCLASSIFIED);
        assertAllowed(person("d", "desk"), view, LOAD);
        assertThat(authorizer.decide(person("d", "desk"), Target.of(nested), CREATE).allowed()).isFalse();
        // The closed table can still be classified, or it would stay closed for good.
        estate.grant(regulated, "stewards", Privilege.MANAGE_TAGS);
        assertAllowed(person("s", "stewards"), table, LIST, TAG);
        assertDenied(person("s", "stewards"), table, Privilege.SELECT, LOAD);

        estate.classify(table, "confidential");
        assertAllowed(person("d", "desk"), table, LOAD, APPEND, DROP);
    }

    @Test
    void aRegulatedCatalogCoversEveryNamespaceAndFalseIsNotRegulated() {
        NamespaceRecord plain = estate.namespace("Plain");
        CatalogObject table = estate.table(plain, "t");
        estate.grantOnCatalog("desk", Privilege.SELECT);
        estate.attribute(table.securable(), GovernanceAttribute.Kind.REGULATED, "false");
        assertAllowed(person("d", "desk"), table, LOAD);

        estate.attribute(new Securable(Securable.Kind.CATALOG, estate.catalogId), GovernanceAttribute.Kind.REGULATED,
                "true");
        assertReason(person("d", "desk"), table, LOAD, Reason.UNCLASSIFIED);
    }

    @Test
    void aMissingPrivilegeIsReportedBeforeAnyGovernanceRule() {
        NamespaceRecord regulated = estate.namespace("Order");
        estate.regulated(regulated);
        CatalogObject table = estate.table(regulated, "t");

        assertReason(person("x", "nobody"), table, LOAD, Reason.NO_PRIVILEGE);
    }

    @Test
    void aFlaggedTableGivesStorageAccessOnlyToTrustedEngines() {
        CatalogObject table = estate.table(estate.namespace("Flagged"), "customers");
        estate.flagRowColumnPolicy(table);
        estate.grantOnCatalog("analysts", Privilege.MODIFY);
        estate.grantOnCatalog("trino-service", Privilege.DESCRIBE);
        Principal bob = new Principal("bob", Set.of("analysts"));

        assertReason(person("bob", "analysts"), table, LOAD, Reason.FLAGGED_TABLE);
        assertReason(person("bob", "analysts"), table, APPEND, Reason.FLAGGED_TABLE);
        assertAllowed(person("bob", "analysts"), table, LIST, DROP);
        Caller trino = new Caller(new Principal("svc-trino", Set.of("trino-service")), "trino", true, Optional.of(bob));
        assertAllowed(trino, table, LOAD, APPEND);
        // An unregistered client asserting bob never gets this far: the authenticator refuses it with 403.
    }

    @Test
    void theIdentityLessWriteSwitchIsOffUntilTurnedOn() {
        NamespaceRecord regulated = estate.namespace("Ledger");
        estate.regulated(regulated);
        CatalogObject table = estate.table(regulated, "entries");
        estate.classify(table, "restricted");
        CatalogObject open = estate.table(estate.namespace("Open"), "entries");
        estate.grantOnCatalog("spark-service", Privilege.MODIFY);
        Caller asItself = new Caller(new Principal("svc-spark", Set.of("spark-service")), "spark", true,
                Optional.empty());
        Caller forCarol = new Caller(new Principal("svc-spark", Set.of("spark-service")), "spark", true,
                Optional.of(new Principal("carol", Set.of("spark-service"))));
        Caller ownService = new Caller(new Principal("svc-batch", Set.of("spark-service")), "batch", false,
                Optional.empty());

        assertAllowed(asItself, table, APPEND, ALTER);
        Authorizer strict = authorizer(true);
        assertThat(strict.decide(asItself, Target.of(table), APPEND)).isInstanceOfSatisfying(Decision.Denied.class,
                d -> assertThat(d.reason()).isEqualTo(Reason.IDENTITY_LESS));
        assertThat(strict.decide(asItself, Target.of(table), DROP).allowed()).isFalse();
        assertThat(strict.decide(asItself, Target.of(table), LOAD).allowed()).isTrue();
        assertThat(strict.decide(forCarol, Target.of(table), APPEND).allowed()).isTrue();
        assertThat(strict.decide(ownService, Target.of(table), APPEND).allowed()).isTrue();
        assertThat(strict.decide(asItself, Target.of(open), APPEND).allowed()).isTrue();
    }

    @Test
    void aBatchIsDecidedInOrder() {
        NamespaceRecord ns = estate.namespace("Batch");
        CatalogObject a = estate.table(ns, "a");
        CatalogObject b = estate.table(ns, "b");
        estate.grant(a, "team", Privilege.SELECT);

        List<Decision> decisions = authorizer.decide(person("t", "team"), List.of(
                new Request(Target.of(a), LOAD), new Request(Target.of(b), LOAD),
                new Request(Target.of(ns), LIST), new Request(Target.of(a), APPEND)));

        assertThat(decisions).extracting(Decision::allowed).containsExactly(true, false, false, false);
        assertThat(((Decision.Denied) decisions.get(3)).privilege()).isEqualTo(Privilege.WRITE);
    }

    private static Caller person(String id, String... groups) {
        return new Caller(new Principal(id, Set.of(groups)), "spark-client", false, Optional.empty());
    }

    private void assertAllowed(Caller caller, CatalogObject object, Operation... operations) {
        for (Operation operation : operations) {
            assertThat(authorizer.decide(caller, Target.of(object), operation))
                    .as("%s %s %s", caller.subject().id(), operation, object.name().display())
                    .isInstanceOf(Decision.Allowed.class);
        }
    }

    private void assertDenied(Caller caller, CatalogObject object, Privilege missing, Operation... operations) {
        assertThat(operations).isNotEmpty();
        for (Operation operation : operations) {
            assertThat(authorizer.decide(caller, Target.of(object), operation))
                    .as("%s %s", caller.subject().id(), operation)
                    .isInstanceOfSatisfying(Decision.Denied.class, d -> {
                        assertThat(d.reason()).isEqualTo(Reason.NO_PRIVILEGE);
                        assertThat(d.privilege()).isEqualTo(operation.privilege());
                    });
        }
        assertThat(Arrays.stream(operations).map(Operation::privilege)).contains(missing);
    }

    private void assertReason(Caller caller, CatalogObject object, Operation operation, Reason reason) {
        assertThat(authorizer.decide(caller, Target.of(object), operation))
                .isInstanceOfSatisfying(Decision.Denied.class, d -> assertThat(d.reason()).isEqualTo(reason));
    }
}
