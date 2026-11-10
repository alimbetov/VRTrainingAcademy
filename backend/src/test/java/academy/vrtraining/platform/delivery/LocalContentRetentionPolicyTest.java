package academy.vrtraining.platform.delivery;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class LocalContentRetentionPolicyTest {
    private LocalContentRetentionPolicy.InstalledContent item(String id, long ageSeconds,
            boolean active, boolean pinned, boolean busy, boolean protectedFromEviction) {
        return new LocalContentRetentionPolicy.InstalledContent(id, 100,
                Instant.parse("2026-11-10T12:00:00Z").minusSeconds(ageSeconds),
                active, pinned, busy, protectedFromEviction);
    }

    @Test void catalogIsNotAnInstallationAndFreeSlotsRequireNoEviction() {
        var decision = LocalContentRetentionPolicy.decide(List.of(
                item("world1", 100, false, false, false, false)), "world2", 3);
        assertTrue(decision.allowed());
        assertTrue(decision.evictReleaseId().isEmpty());
    }

    @Test void fourthPackageReplacesLeastRecentlyOpened() {
        var decision = LocalContentRetentionPolicy.decide(List.of(
                item("recent", 10, false, false, false, false),
                item("oldest", 900, false, false, false, false),
                item("middle", 100, false, false, false, false)), "fourth", 3);
        assertEquals("oldest", decision.evictReleaseId().orElseThrow());
        assertEquals("LRU_REPLACEMENT", decision.reason());
    }

    @Test void activePinnedBusyAndProtectedContentCannotBeEvicted() {
        var decision = LocalContentRetentionPolicy.decide(List.of(
                item("active", 900, true, false, false, false),
                item("pinned", 800, false, true, false, false),
                item("candidate", 100, false, false, false, false)), "fourth", 3);
        assertEquals("candidate", decision.evictReleaseId().orElseThrow());

        var denied = LocalContentRetentionPolicy.decide(List.of(
                item("active", 900, true, false, false, false),
                item("pinned", 800, false, true, false, false),
                item("busy", 700, false, false, true, false)), "fourth", 3);
        assertFalse(denied.allowed());
        assertEquals("ALL_CONTENT_PROTECTED", denied.reason());
    }

    @Test void unsynchronizedOrOtherwiseProtectedContentIsNotSelected() {
        var result = LocalContentRetentionPolicy.decide(List.of(
                item("unsynced", 900, false, false, false, true),
                item("safe", 200, false, false, false, false),
                item("recent", 20, false, false, false, false)), "fourth", 3);
        assertEquals("safe", result.evictReleaseId().orElseThrow());
    }

    @Test void retrySameReleaseDoesNotTriggerDeletion() {
        var result = LocalContentRetentionPolicy.decide(List.of(
                item("one", 90, false, false, false, false),
                item("two", 80, false, false, false, false),
                item("three", 70, false, false, false, false)), "two", 3);
        assertTrue(result.allowed());
        assertTrue(result.evictReleaseId().isEmpty());
        assertEquals("ALREADY_INSTALLED", result.reason());
    }

    @Test void overCapacityAndInvalidInputsFailClosed() {
        var four = List.of(item("a", 1, false, false, false, false),
                item("b", 2, false, false, false, false),
                item("c", 3, false, false, false, false),
                item("d", 4, false, false, false, false));
        var result = LocalContentRetentionPolicy.decide(four, "e", 3);
        assertFalse(result.allowed());
        assertEquals("RECONCILIATION_REQUIRED", result.reason());
        assertThrows(IllegalArgumentException.class,
                () -> LocalContentRetentionPolicy.decide(List.of(), "new", 0));
    }
}
