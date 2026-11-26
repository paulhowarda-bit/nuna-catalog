package nuna.catalog.audit;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A catalog's branch or tag made, renewed, deleted, merged, warned of before its expiry, or ended at it (D33, item
 * 26's choices 11 and 13), or a table or view taken out of it (backlog 156). The record is about the ref: its subject
 * is the ref's id, so one ref's events keep their order, and its audit row names the catalog. It goes to the audit feed
 * and to the change stream, which is how the catalog tells people a branch is about to expire.
 *
 * @param action    what happened
 * @param ref       the ref's id, which never names another ref once it is deleted
 * @param name      the ref's name, as given
 * @param kind      branch or tag
 * @param catalog   the catalog the ref belongs to
 * @param from      what the ref was made from: {@code main}, or another ref's name then
 * @param expiresAt a branch's expiry after the change; null for a tag, which never expires
 * @param description the purpose its maker gave the ref, in the record of its creation; null when it was made without
 *                    one, and for every other action (schema version 2, backlog 167)
 * @param tables    the tables and views a merge moved onto main, or the one taken out of the ref; empty for every
 *                  other action
 * @param actor     who made the change: the caller, or for an expiry the maintenance principal
 */
public record RefChange(Action action, UUID ref, String name, Kind kind, Securable catalog, String from,
                        Instant expiresAt, String description, List<Securable> tables, Actor actor)
        implements EventData {

    public enum Action { CREATED, RENEWED, DELETED, MERGED, EXPIRING, EXPIRED, OBJECT_REMOVED }

    public enum Kind { BRANCH, TAG }

    public RefChange {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(from, "from");
        tables = List.copyOf(Objects.requireNonNull(tables, "tables"));
        Objects.requireNonNull(actor, "actor");
    }

    @Override
    public EventType type() {
        return EventType.REF;
    }

    @Override
    public String subject() {
        return ref.toString();
    }
}
