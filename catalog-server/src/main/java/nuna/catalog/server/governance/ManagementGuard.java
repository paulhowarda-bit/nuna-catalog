package nuna.catalog.server.governance;

import nuna.catalog.core.commit.GovernanceChain;
import nuna.catalog.security.Authorizer;
import nuna.catalog.security.Authorizer.Decision;
import nuna.catalog.security.Authorizer.Operation;
import nuna.catalog.security.Privilege;
import nuna.catalog.security.Separation;
import nuna.catalog.server.governance.Management.Request;
import nuna.catalog.server.governance.Management.Target;
import nuna.catalog.server.rest.AccessDenied;
import nuna.catalog.server.security.Denials;
import nuna.catalog.server.security.Holdings;
import nuna.catalog.server.security.SecuritySettings;
import nuna.catalog.store.GovernanceAttribute;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Authorization of the M7 management routes (D18, D25, D37), asked once the path's target is resolved and
 * before any other work. Privileges go through M5's {@link Authorizer}; the DR freeze is for platform
 * operators and the purge queue for maintenance principals, as the trusted-engine registry is for operators.
 * Every refusal is audited through {@link Denials}. Names are hidden as on the Iceberg routes (D18, amended
 * 2026-11-19): a caller refused on a namespace, table or view it holds nothing on ({@link Holdings}) gets the
 * target's answer for a path that names nothing; any other refusal answers 403. A caller that holds nothing on the
 * catalog is answered before it gets here, as for a catalog that does not exist ({@link Management#catalog}, item
 * 68). The choice per route is in build-plan.md, M7.
 */
@Component
class ManagementGuard {

    private final Authorizer authorizer;
    private final Separation separation;
    private final SecuritySettings security;
    private final Denials denials;
    private final Holdings holdings;

    ManagementGuard(Authorizer authorizer, Separation separation, SecuritySettings security, Denials denials,
                    Holdings holdings) {
        this.authorizer = authorizer;
        this.separation = separation;
        this.security = security;
        this.denials = denials;
        this.holdings = holdings;
    }

    /**
     * {@code privilege} on the target, through grants on it or above it. A caller that holds nothing on the
     * namespace, table or view gets {@link Target#missing}.
     */
    void require(Request request, Target target, String operation, Privilege privilege) {
        Authorizer.Target on = on(target);
        if (authorizer.decide(request.caller(), on, new Operation(operation, privilege, false))
                instanceof Decision.Denied denied) {
            throw hidden(request, on, target, refuse(request, target.audit(), operation, denied));
        }
    }

    /**
     * Whether the caller holds {@code privilege} on the target, asked to decide what an answer holds rather than
     * whether to give it: nothing is refused, so nothing is audited.
     */
    boolean allows(Request request, Target target, String operation, Privilege privilege) {
        return authorizer.decide(request.caller(), on(target), new Operation(operation, privilege, false)).allowed();
    }

    /** {@code privilege} on {@code on}, which audit records name {@code named}; a refusal is 403. */
    void require(Request request, Authorizer.Target on, nuna.catalog.audit.Securable named, String operation,
                 Privilege privilege) {
        if (authorizer.decide(request.caller(), on, new Operation(operation, privilege, false))
                instanceof Decision.Denied denied) {
            throw refuse(request, named, operation, denied);
        }
    }

    /**
     * A platform operator (D18), on the target: the DR freeze is a deployment action, like the trusted-engine
     * registry. Any other caller that holds nothing on the namespace, table or view gets {@link Target#missing}.
     */
    void requireOperatorOn(Request request, Target target, String operation) {
        if (!operator(request)) {
            throw hidden(request, on(target), target, notOperator(request, target.audit(), operation));
        }
    }

    /** A platform operator (D18), for a route on no namespace, table or view; a refusal is 403. */
    void requireOperator(Request request, nuna.catalog.audit.Securable target, String operation) {
        if (!operator(request)) {
            throw notOperator(request, target, operation);
        }
    }

    /** A maintenance principal acting as itself (D25); {@code target} is null for the cross-catalog purge queue. */
    void requireMaintenance(Request request, nuna.catalog.audit.Securable target, String operation) {
        if (!security.isMaintenance(request.caller())) {
            throw denials.refuse(request.actor(), operation, target, null, "not_maintenance",
                    "only a maintenance principal may call " + operation);
        }
    }

    /** A refusal under a legal hold, audited as a denial with reason {@code legal_hold} (D18, D24). */
    AccessDenied held(Request request, nuna.catalog.audit.Securable target, String operation, String message) {
        return denials.refuse(request.actor(), operation, target, null, "legal_hold", message);
    }

    /**
     * Refuses {@code operation} on {@code target} when a DR freeze covers the chain (D37), whatever the caller holds:
     * audited as a denial with reason {@code dr_freeze} (D18), and answered 403 with the freeze's reason.
     */
    void requireUnfrozen(Request request, GovernanceChain chain, nuna.catalog.audit.Securable target,
                         String operation, String what) {
        chain.nearest(GovernanceAttribute.Kind.DR_FREEZE).ifPresent(freeze -> {
            throw frozen(request, target, operation, what + " is frozen for DR reconcile: " + freeze.value());
        });
    }

    /** A refusal under a DR freeze, audited as a denial with reason {@code dr_freeze} (D18, D37). */
    AccessDenied frozen(Request request, nuna.catalog.audit.Securable target, String operation, String message) {
        return denials.refuse(request.actor(), operation, target, null, "dr_freeze", message);
    }

    private boolean operator(Request request) {
        return request.caller().subject().groups().stream().anyMatch(separation.platformOperatorGroups()::contains);
    }

    private AccessDenied notOperator(Request request, nuna.catalog.audit.Securable target, String operation) {
        return denials.refuse(request.actor(), operation, target, null, "no_privilege",
                "only platform operators may call " + operation);
    }

    /**
     * A refusal by main's commit rules, a legal hold, a DR freeze or the minimum snapshot age among them, of
     * {@code operation} on {@code named}, which needed {@code privilege}: audited as a denial with the rules' reason,
     * as {@code Guard.refused} audits a commit's (D18), and 403.
     */
    AccessDenied ruled(Request request, nuna.catalog.audit.Securable named, String operation, Privilege privilege,
                       String reason, String message) {
        return denials.refuse(request.actor(), operation, named, privilege.wireName(), reason, message);
    }

    /** The authorizer's refusal of {@code operation} on {@code named}, audited as a denial (D18); 403. */
    AccessDenied refuse(Request request, nuna.catalog.audit.Securable named, String operation,
                        Decision.Denied denied) {
        return denials.refuse(request.actor(), operation, named,
                denied.privilege() == null ? null : denied.privilege().wireName(),
                denied.reason().name().toLowerCase(Locale.ROOT), denied.message());
    }

    /** The refusal, already audited: 403 to a caller that holds something on the target, else the missing answer. */
    private RuntimeException hidden(Request request, Authorizer.Target on, Target target, AccessDenied refused) {
        return holdings.something(request.caller(), on) ? refused : target.missing();
    }

    private static Authorizer.Target on(Target target) {
        return switch (target.securable().kind()) {
            case CATALOG -> new Authorizer.Target.Catalog(target.catalog().id());
            case NAMESPACE -> new Authorizer.Target.Namespace(target.catalog().id(), target.securable().id());
            case TABLE, VIEW -> new Authorizer.Target.TableOrView(target.object().orElseThrow());
        };
    }
}
