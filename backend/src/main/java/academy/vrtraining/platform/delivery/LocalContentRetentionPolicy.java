package academy.vrtraining.platform.delivery;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Pure admission/eviction decision logic for on-demand downloaded content.
 * This class NEVER deletes files and never manipulates learner progress.
 * A separate crash-safe coordinator must install and validate new content
 * BEFORE applying the selected eviction.
 */
public final class LocalContentRetentionPolicy {
    public static final int DEFAULT_MAX_INSTALLED_RELEASES = 3;
    private LocalContentRetentionPolicy() {}

    public record InstalledContent(String releaseId, long bytes, Instant lastOpenedAt,
                                   boolean active, boolean pinned, boolean busy,
                                   boolean protectedFromEviction) {
        public InstalledContent {
            if (releaseId == null || releaseId.isBlank()) {
                throw new IllegalArgumentException("releaseId");
            }
            if (bytes < 0) throw new IllegalArgumentException("bytes");
            Objects.requireNonNull(lastOpenedAt, "lastOpenedAt");
        }
        public boolean evictable() {
            return !active && !pinned && !busy && !protectedFromEviction;
        }
    }

    public record Decision(boolean allowed, Optional<String> evictReleaseId, String reason) {
        public Decision {
            Objects.requireNonNull(evictReleaseId);
            Objects.requireNonNull(reason);
        }
    }

    /**
     * Policy for adding a new installation. Does not inspect or delete persistent files.
     * Caller MUST reserve sufficient space for staging+installation prior to invoking
     * filesystem operations. Retention is a separate post-activation transaction.
     * Existing release installations do not consume an additional slot.
     */
    public static Decision decide(List<InstalledContent> installed, String newReleaseId,
                                  int maxInstalled) {
        Objects.requireNonNull(installed);
        if (newReleaseId == null || newReleaseId.isBlank())
            throw new IllegalArgumentException("newReleaseId");
        if (maxInstalled < 1) throw new IllegalArgumentException("maxInstalled");
        Set<String> uniqueIds = new java.util.HashSet<>();
        for (InstalledContent item : installed) {
            Objects.requireNonNull(item);
            if (!uniqueIds.add(item.releaseId()))
                throw new IllegalArgumentException("Duplicate installed release");
        }
        if (uniqueIds.contains(newReleaseId)) {
            return new Decision(true, Optional.empty(), "ALREADY_INSTALLED");
        }
        if (installed.size() < maxInstalled) {
            return new Decision(true, Optional.empty(), "SPACE_IN_SLOT_LIMIT");
        }
        // A single-choice plan applies when the local cache is exactly at capacity.
        // Unexpected over-capacity needs reconciliation, not arbitrary deletion.
        if (installed.size() > maxInstalled)
            return new Decision(false, Optional.empty(), "RECONCILIATION_REQUIRED");
        return installed.stream()
                .filter(InstalledContent::evictable)
                .min(Comparator.comparing(InstalledContent::lastOpenedAt)
                        .thenComparing(InstalledContent::releaseId))
                .map(item -> new Decision(true, Optional.of(item.releaseId()), "LRU_REPLACEMENT"))
                .orElseGet(() -> new Decision(false, Optional.empty(), "ALL_CONTENT_PROTECTED"));
    }
}
