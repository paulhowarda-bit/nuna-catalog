package nuna.catalog.server.governance;

import static nuna.catalog.server.governance.Management.ROOT;

import nuna.catalog.core.CatalogError;
import nuna.catalog.server.governance.Management.Request;
import nuna.catalog.store.CatalogObject;
import nuna.catalog.store.CatalogStore;
import nuna.catalog.store.DrState;
import nuna.catalog.store.DrWatermark;
import nuna.catalog.store.JournalRecord;
import nuna.catalog.store.JournalStore;
import nuna.catalog.store.Page;
import nuna.catalog.store.PageRequest;
import nuna.catalog.store.Pointer;
import nuna.catalog.store.PointerKey;
import nuna.catalog.store.WriteResult;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The DR replication watermark (D37, amended 2026-11-20; docs/observability/dr-watermark.md). The maintenance
 * worker's verifier, a maintenance principal acting as itself, reads its work list (each table and view behind its
 * watermark, with the journaled versions above it) and reports how far it found the DR store whole; platform
 * operators read the tables behind, by name (D18, amended 2026-11-20). A report is the worker's own bookkeeping, not
 * a change to the catalog, and writes no audit record; a refusal by the guard is audited, as on every management
 * route.
 */
@RestController
class DrRoutes {

    static final String WORK = ROOT + "/maintenance/dr-work";
    static final String WATERMARK = ROOT + "/maintenance/tables/{id}/dr-watermark";
    static final String WATERMARKS = ROOT + "/dr/watermarks";

    /**
     * Versions above its watermark one work item lists at most; the verifier reads the rest at its next pass. Without
     * a watermark, the newest are listed, for the verifier's whole check (item 99).
     */
    static final int MAX_VERSIONS = 100;
    private static final int WORK_PAGE = 100;
    private static final int MAX_WORK_PAGE = 1000;
    private static final int READOUT_PAGE = 50;
    private static final int MAX_READOUT_PAGE = 200;
    private static final int MAX_LOCATION = 4000;

    private final CatalogStore store;
    private final Management management;
    private final ManagementGuard guard;
    private final CatalogNames catalogs;
    private final Clock clock;

    DrRoutes(CatalogStore store, Management management, ManagementGuard guard, CatalogNames catalogs, Clock clock) {
        this.store = store;
        this.management = management;
        this.guard = guard;
        this.catalogs = catalogs;
        this.clock = clock;
    }

    /**
     * The verifier's work list: every active table and view whose watermark is below its main pointer, or which has
     * none, by id. Each item holds its location, its pointer, its watermark when it has one, the journaled versions
     * above the watermark (at most {@value #MAX_VERSIONS}, oldest first; without a watermark the newest, up to the
     * pointer's, from which the verifier takes the newest it finds whole: D37, amended again 2026-11-20, item 99) and
     * when the oldest version above the watermark was committed.
     */
    @GetMapping(path = WORK, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> work(@RequestParam(required = false) String pageToken,
                                @RequestParam(required = false) Integer pageSize, HttpServletRequest http) {
        guard.requireMaintenance(management.request(http), null, "listDrWork");
        Page<DrState> page = behind(pageToken, pageSize == null ? WORK_PAGE : Math.clamp(pageSize, 1, MAX_WORK_PAGE));
        ObjectNode body = Management.JSON.createObjectNode();
        ArrayNode objects = body.putArray("objects");
        for (DrState state : page.items()) {
            ObjectNode item = objects.addObject();
            item.put("id", state.object().id().toString());
            item.put("kind", state.object().kind().name().toLowerCase(Locale.ROOT));
            item.put("location", state.object().locationRoot());
            ObjectNode pointer = item.putObject("pointer");
            pointer.put("version", state.version());
            pointer.put("metadata-location", state.metadataLocation());
            state.watermark().ifPresent(watermark -> {
                ObjectNode kept = item.putObject("watermark");
                kept.put("version", watermark.version());
                kept.put("metadata-location", watermark.metadataLocation());
            });
            ArrayNode versions = item.putArray("versions");
            long after = state.watermark().map(DrWatermark::version)
                    .orElse(Math.max(0, state.version() - MAX_VERSIONS));
            List<JournalRecord> listed = journal(state.object().id(), after, MAX_VERSIONS);
            for (JournalRecord record : listed) {
                ObjectNode version = versions.addObject();
                version.put("version", record.newVersion());
                version.put("metadata-location", record.newLocation());
                version.put("commit-id", record.commitId().toString());
                version.put("committed-at", record.committedAt().toString());
            }
            (after == 0 || state.watermark().isPresent() ? listed : journal(state.object().id(), 0, 1)).stream()
                    .findFirst().ifPresent(oldest -> item.put("oldest-unverified-at",
                            oldest.committedAt().toString()));
        }
        page.nextToken().ifPresent(token -> body.put("next-page-token", token));
        return GovernanceRoutes.json(200, body);
    }

    /**
     * The verifier's report: {@code {"version": n, "metadata-location": "..."}}, a version of the table's or view's
     * main pointer whose every file it found in the DR store. The location must be the one the journal records for
     * that version, or the pointer's. Answers 204, also when that version is already kept; 404 when the object is no
     * active table or view; 409 when a higher watermark is kept, the version is above the pointer, or the location is
     * not that version's.
     */
    @PutMapping(path = WATERMARK)
    ResponseEntity<Void> record(@PathVariable String id, @RequestBody(required = false) String body,
                                HttpServletRequest http) {
        Request request = management.request(http);
        guard.requireMaintenance(request, audit(id), "recordDrWatermark");
        UUID objectId = uuid(id);
        JsonNode json = Management.body(body);
        JsonNode versionField = json.get("version");
        if (versionField == null || !versionField.isIntegralNumber() || !versionField.canConvertToLong()
                || versionField.longValue() < 1) {
            throw CatalogError.badRequest("Missing field version: a pointer version, from 1");
        }
        long version = versionField.longValue();
        String location = Management.text(json, "metadata-location", MAX_LOCATION);
        CatalogObject object = store.readObject(objectId).filter(found -> found.state() == CatalogObject.State.ACTIVE)
                .orElseThrow(() -> gone(objectId));
        Pointer pointer = store.readPointer(object.mainKey()).orElseThrow(() -> gone(objectId));
        if (version > pointer.version()) {
            throw ahead(version, pointer.version());
        }
        String journaled = version == pointer.version() ? pointer.metadataLocation()
                : journal(objectId, version - 1, 1).stream().filter(record -> record.newVersion() == version)
                        .map(JournalRecord::newLocation).findFirst().orElseThrow(() -> CatalogError.of(409,
                                "UnknownVersionException", "The journal holds no version " + version + " of " + objectId));
        if (!journaled.equals(location)) {
            throw CatalogError.of(409, "WatermarkMismatchException", "Version " + version + " of " + objectId
                    + " is " + journaled + ", not " + location);
        }
        WriteResult result = store.recordDrWatermark(objectId, new DrWatermark(version, location,
                clock.instant().truncatedTo(ChronoUnit.MILLIS)));
        return switch (result) {
            case WriteResult.Applied applied -> ResponseEntity.noContent().build();
            case WriteResult.Unchanged unchanged -> ResponseEntity.noContent().build();
            case WriteResult.Missing missing -> throw gone(objectId);
            case WriteResult.Refused refused when refused.reason() == WriteResult.Reason.WATERMARK_BEHIND ->
                    throw CatalogError.of(409, "WatermarkBehindException", "A higher DR watermark is kept for "
                            + objectId + " than version " + version + ", and a watermark never moves back");
            case WriteResult.Refused refused when refused.reason() == WriteResult.Reason.WATERMARK_AHEAD ->
                    throw ahead(version, pointer.version());
            default -> throw new IllegalStateException("a DR watermark report answered " + result);
        };
    }

    /**
     * The tables and views behind their DR watermark, for platform operators, by name (D18, amended 2026-11-20): each
     * with its pointer's version, its watermark (none when the verifier has passed none of its versions), how many
     * versions behind it is, and its lag, the time since its oldest unverified commit. By id, {@code pageSize} at a
     * time (default 50, at most 200).
     */
    @GetMapping(path = WATERMARKS, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> readout(@RequestParam(required = false) String pageToken,
                                   @RequestParam(required = false) Integer pageSize, HttpServletRequest http) {
        guard.requireOperator(management.request(http), null, "listDrWatermarks");
        Page<DrState> page = behind(pageToken, pageSize == null ? READOUT_PAGE
                : Math.clamp(pageSize, 1, MAX_READOUT_PAGE));
        Instant now = clock.instant();
        ObjectNode body = Management.JSON.createObjectNode();
        ArrayNode tables = body.putArray("tables");
        for (DrState state : page.items()) {
            CatalogObject object = state.object();
            ObjectNode item = tables.addObject();
            item.put("id", object.id().toString());
            item.put("kind", object.kind().name().toLowerCase(Locale.ROOT));
            Optional.ofNullable(catalogs.name(object.catalogId())).ifPresent(name -> item.put("catalog", name));
            management.levels(object.catalogId(), object.namespaceId()).ifPresent(levels -> {
                ArrayNode namespace = item.putArray("namespace");
                levels.forEach(namespace::add);
            });
            item.put("name", object.name().display());
            item.put("pointer-version", state.version());
            state.watermark().ifPresentOrElse(watermark -> {
                item.put("watermark-version", watermark.version());
                item.put("watermark-recorded-at", watermark.recordedAt().toString());
            }, () -> item.putNull("watermark-version"));
            item.put("versions-behind", state.behind());
            journal(object.id(), state.watermark().map(DrWatermark::version).orElse(0L), 1).stream().findFirst()
                    .ifPresent(oldest -> {
                        item.put("oldest-unverified-at", oldest.committedAt().toString());
                        item.put("lag-seconds", Math.max(0, Duration.between(oldest.committedAt(), now).toSeconds()));
                    });
        }
        page.nextToken().ifPresent(token -> body.put("next-page-token", token));
        return GovernanceRoutes.json(200, body);
    }

    private Page<DrState> behind(String pageToken, int size) {
        try {
            return store.listDrBehind(new PageRequest(Optional.ofNullable(pageToken).filter(token -> !token.isEmpty()),
                    size));
        } catch (IllegalArgumentException e) {
            throw CatalogError.badRequest("Invalid page token: " + pageToken);
        }
    }

    /** Up to {@code limit} journaled versions of the object's main pointer after {@code version}, oldest first. */
    private List<JournalRecord> journal(UUID objectId, long version, int limit) {
        return store.readJournal(new PointerKey(objectId, PointerKey.MAIN), JournalStore.EARLIEST, JournalStore.LATEST,
                JournalStore.afterVersion(version, limit)).items();
    }

    private static CatalogError gone(UUID objectId) {
        return CatalogError.of(404, "NoSuchTableException", "No active table or view " + objectId);
    }

    private static CatalogError ahead(long version, long pointer) {
        return CatalogError.of(409, "WatermarkAheadException", "Version " + version
                + " is above the pointer's version " + pointer);
    }

    private static UUID uuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw CatalogError.badRequest("Not a table or view id: " + id);
        }
    }

    /** The object a report's path names, as a refusal's audit record names it; null when there is none. */
    private nuna.catalog.audit.Securable audit(String id) {
        UUID objectId;
        try {
            objectId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return store.readObject(objectId).map(object -> {
            List<String> path = new ArrayList<>(management.levels(object.catalogId(), object.namespaceId())
                    .orElse(List.of()));
            path.add(object.name().display());
            return new nuna.catalog.audit.Securable(object.kind() == CatalogObject.Kind.TABLE
                    ? nuna.catalog.audit.Securable.Kind.TABLE : nuna.catalog.audit.Securable.Kind.VIEW,
                    object.id(), catalogs.name(object.catalogId()), path);
        }).orElse(null);
    }
}
