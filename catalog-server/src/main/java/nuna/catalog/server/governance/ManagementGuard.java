package nuna.catalog.server.governance;

import nuna.catalog.security.Authorizer;
import nuna.catalog.security.Authorizer.Decision;
import nuna.catalog.security.Authorizer.Operation;
import nuna.catalog.security.Privilege;
import nuna.catalog.security.Separation;
import nuna.catalog.server.governance.Management.Request;
import nuna.catalog.server.governance.Management.Target;
import nuna.catalog.server.security.Denials;
import nuna.catalog.server.security.SecuritySettings;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Authorization of the M7 management routes (D18, D25, D37), asked once the path's target is resolved and
 * before any other work. Privileges go through M5's {@link Authorizer}; the DR freeze is for platform
 * operators and the purge queue for maintenance principals, as the trusted-engine registry is for operators.
 * Every refusal is audited and answers 403 through {@link Denials}. The choice per route is in build-plan.md, M7.
 */
@Component
class ManagementGuard {

    private final Authorizer authorizer;
    private final Separation separation;
    private final SecuritySettings security;
    private final Denials denials;

    ManagementGuard(Authorizer authorizer, Separation separation, SecuritySettings security, Denials denials) {
        this.authorizer = authorizer;
        this.separation = separation;
        this.security = security;
        this.denials = denials;
    }

    /** {@code privilege} on the target, through grants on it or above it. */
    void require(Request request, Target target, String operation, Privilege privilege) {
        Authorizer.Target on = switch (target.securable().kind()) {
            case CATALOG -> new Authorizer.Target.Catalog(target.catalog().id());
            case NAMESPACE -> new Authorizer.Target.Namespace(target.catalog().id(), target.securable().id());
            case TABLE, VIEW -> new Authorizer.Target.TableOrView(target.object().orElseThrow());
        };
        require(request, on, target.audit(), operation, privilege);
    }

    /** {@code privilege} on {@code on}, which audit records name {@code named}. */
    void require(Request request, Authorizer.Target on, nuna.catalog.audit.Securable named, String operation,
                 Privilege privilege) {
        if (authorizer.decide(request.caller(), on, new Operation(operation, privilege, false))
                instanceof Decision.Denied denied) {
            throw denials.refuse(request.actor(), operation, named,
                    denied.privilege() == null ? null : denied.privilege().wireName(),
                    denied.reason().name().toLowerCase(Locale.ROOT), denied.message());
        }
    }

    /** A platform operator (D18): the DR freeze is a deployment action, like the trusted-engine registry. */
    void requireOperator(Request request, nuna.catalog.audit.Securable target, String operation) {
        if (request.caller().subject().groups().stream().noneMatch(separation.platformOperatorGroups()::contains)) {
            throw denials.refuse(request.actor(), operation, target, null, "no_privilege",
                    "only platform operators may call " + operation);
        }
    }

    /** A maintenance principal acting as itself (D25); {@code target} is null for the cross-catalog purge queue. */
    void requireMaintenance(Request request, nuna.catalog.audit.Securable target, String operation) {
        if (!security.isMaintenance(request.caller())) {
            throw denials.refuse(request.actor(), operation, target, null, "not_maintenance",
                    "only a maintenance principal may call " + operation);
        }
    }
}
