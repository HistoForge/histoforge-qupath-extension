package qupath.ext.histoforge.ui.project;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import qupath.ext.histoforge.contract.BuildIdentityCodec.ClientBuildIdentity;
import qupath.ext.histoforge.pipeline.PersistedActionRecovery;

/** Blocks new selected-image mutation until one retained post-restart target is resolved. */
final class ProjectPersistedRecoveryGate {

    record Availability(boolean canResume, String unavailableReason) {
        Availability {
            unavailableReason = Objects.requireNonNull(unavailableReason, "unavailableReason");
            if (canResume && !unavailableReason.isEmpty()) {
                throw new IllegalArgumentException(
                        "available recovery must not carry an unavailable reason");
            }
            if (!canResume && unavailableReason.isBlank()) {
                throw new IllegalArgumentException(
                        "unavailable recovery must explain why resume is blocked");
            }
        }
    }

    private ProjectPersistedRecoveryGate() {}

    static List<PersistedActionRecovery.Item> selectedUnresolved(
            List<ImageRowModel> rows, List<PersistedActionRecovery.Item> unresolved) {
        Set<String> selected =
                Objects.requireNonNull(rows, "rows").stream()
                        .map(row -> row.entry.getID())
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return Objects.requireNonNull(unresolved, "unresolved").stream()
                .filter(item -> selected.contains(item.imageId()))
                .toList();
    }

    static Availability availability(
            PersistedActionRecovery.Item item, ClientBuildIdentity runningBuild) {
        PersistedActionRecovery.Item retained = Objects.requireNonNull(item, "item");
        ClientBuildIdentity current = Objects.requireNonNull(runningBuild, "runningBuild");
        if (!retained.clientBuild().equals(current)) {
            return new Availability(
                    false,
                    "this unfinished analysis requires the exact extension build that started it");
        }
        return new Availability(true, "");
    }
}
