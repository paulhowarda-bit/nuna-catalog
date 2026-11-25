package nuna.catalog.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The ladder (D18) and separation of duties between groups (D18, D19). */
class SeparationTest {

    private final Separation separation = new Separation(Set.of("grant-admins"), Set.of("platform-ops"));

    @Test
    void theLadderImpliesDownwardOnly() {
        assertThat(Privilege.MODIFY.implies(Privilege.WRITE)).isTrue();
        assertThat(Privilege.MODIFY.implies(Privilege.SELECT)).isTrue();
        assertThat(Privilege.WRITE.implies(Privilege.SELECT)).isTrue();
        assertThat(Privilege.SELECT.implies(Privilege.DESCRIBE)).isTrue();
        assertThat(Privilege.SELECT.implies(Privilege.WRITE)).isFalse();
        assertThat(Privilege.WRITE.implies(Privilege.MODIFY)).isFalse();
        assertThat(Privilege.DESCRIBE.implies(Privilege.SELECT)).isFalse();
    }

    @Test
    void privilegesBesideTheLadderGiveOnlyThemselvesAndDescribe() {
        for (Privilege apart : List.of(Privilege.CREATE, Privilege.MANAGE_GRANTS, Privilege.MANAGE_TAGS)) {
            for (Privilege other : Privilege.values()) {
                assertThat(apart.implies(other)).as("%s implies %s", apart, other)
                        .isEqualTo(other == apart || other == Privilege.DESCRIBE);
            }
        }
        for (Privilege any : Privilege.values()) {
            assertThat(any.implies(Privilege.MANAGE_GRANTS)).isEqualTo(any == Privilege.MANAGE_GRANTS);
        }
    }

    @Test
    void wireNamesRoundTripAndUnknownNamesAreRefused() {
        assertThat(Privilege.MANAGE_GRANTS.wireName()).isEqualTo("manage-grants");
        for (Privilege privilege : Privilege.values()) {
            assertThat(Privilege.fromWireName(privilege.wireName())).isEqualTo(privilege);
        }
        assertThatIllegalArgumentException().isThrownBy(() -> Privilege.fromWireName("all"));
        assertThatIllegalArgumentException().isThrownBy(() -> Privilege.fromWireName("SELECT"));
    }

    @Test
    void aGrantAdminGroupHoldsNothingElse() {
        assertThat(separation.refusal("g", Privilege.SELECT, List.of(Privilege.MANAGE_GRANTS))).isPresent();
        assertThat(separation.refusal("g", Privilege.DESCRIBE, List.of(Privilege.MANAGE_GRANTS))).isPresent();
        assertThat(separation.refusal("g", Privilege.MANAGE_TAGS, List.of(Privilege.MANAGE_GRANTS))).isPresent();
        assertThat(separation.refusal("g", Privilege.MANAGE_GRANTS, List.of(Privilege.DESCRIBE))).isPresent();
        assertThat(separation.refusal("g", Privilege.MANAGE_GRANTS, List.of(Privilege.MANAGE_GRANTS))).isEmpty();
    }

    @Test
    void aTagStewardMayReadButNotChangeData() {
        assertThat(separation.refusal("g", Privilege.SELECT, List.of(Privilege.MANAGE_TAGS))).isEmpty();
        assertThat(separation.refusal("g", Privilege.WRITE, List.of(Privilege.MANAGE_TAGS))).isPresent();
        assertThat(separation.refusal("g", Privilege.CREATE, List.of(Privilege.MANAGE_TAGS))).isPresent();
        assertThat(separation.refusal("g", Privilege.MANAGE_TAGS, List.of(Privilege.MODIFY))).isPresent();
    }

    @Test
    void theReasonNamesTheSameConflictWhateverOrderTheGrantsAreReadIn() {
        String reason = "group g holds describe in this catalog, a read duty that manage-grants (grants) must stay"
                + " separate from";
        assertThat(separation.refusal("g", Privilege.MANAGE_GRANTS, List.of(Privilege.MANAGE_TAGS,
                Privilege.SELECT, Privilege.DESCRIBE))).hasValue(reason);
        assertThat(separation.refusal("g", Privilege.MANAGE_GRANTS, List.of(Privilege.DESCRIBE,
                Privilege.MANAGE_TAGS, Privilege.SELECT))).hasValue(reason);
    }

    @Test
    void aDataAdminMayHoldTheWholeLadderAndCreate() {
        assertThat(separation.refusal("g", Privilege.CREATE, List.of(Privilege.MODIFY, Privilege.SELECT))).isEmpty();
        assertThat(separation.refusal("g", Privilege.MODIFY, List.of(Privilege.CREATE))).isEmpty();
    }

    @Test
    void configuredGroupsMayReceiveNoGrant() {
        for (Privilege privilege : Privilege.values()) {
            assertThat(separation.refusal("platform-ops", privilege, List.of())).isPresent();
            assertThat(separation.refusal("grant-admins", privilege, List.of())).isPresent();
        }
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Separation(Set.of("ops", "admins"), Set.of("ops")))
                .withMessageContaining("ops");
    }
}
