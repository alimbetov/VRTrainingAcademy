package academy.vrtraining.platform.content;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class QuestLessonUploadServiceTest {
    private static final UUID ORG = UUID.fromString("a75f8512-6e42-4741-ae7d-1e2a1eb0ea21");

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static QuestLessonUploadService.Request request(byte[] bytes) throws Exception {
        return new QuestLessonUploadService.Request(ORG, "PHY-001-v1", "PHY-001",
                "PHYSICS_BASIC", "Android_ASTC", "5.6", "0.1.0-foundation",
                "pakchunk1001-Android_ASTC.pak", bytes.length, sha(bytes));
    }

    @Test void validUploadStagesOnlyAndCopiesExactVerifiedBytes() throws Exception {
        byte[] payload = "fake-pak-bytes-only-for-ingestion-test".getBytes();
        var calls = new AtomicInteger();
        var s = new QuestLessonUploadService(
                (subject, org) -> subject.equals("teacher") && org.equals(ORG),
                (path, key) -> {
                    calls.incrementAndGet();
                    assertArrayEquals(payload, Files.readAllBytes(path));
                    assertTrue(key.startsWith("quarantine/quest-lessons/" + ORG + "/PHY-001-v1/"));
                    assertTrue(key.endsWith("/pakchunk1001-Android_ASTC.pak"));
                    assertFalse(key.startsWith("published/"));
                });
        var result = s.stage("teacher", request(payload), new ByteArrayInputStream(payload));
        assertEquals("STAGED", result.state());
        assertEquals(1, calls.get());
        assertEquals(sha(payload), result.sha256Hex());
    }

    @Test void unauthorizedSubjectNeverReadsOrStoresArtifact() throws Exception {
        byte[] payload = {1, 2, 3};
        var count = new AtomicInteger();
        var s = new QuestLessonUploadService((who, org) -> false,
                (path, key) -> count.incrementAndGet());
        assertThrows(SecurityException.class,
                () -> s.stage("unauthorized", request(payload), new ByteArrayInputStream(payload)));
        assertEquals(0, count.get());
    }

    @Test void corruptedOrTruncatedBytesAreNotUploaded() throws Exception {
        byte[] valid = {1, 2, 3, 4};
        var count = new AtomicInteger();
        var s = new QuestLessonUploadService((who, org) -> true,
                (path, key) -> count.incrementAndGet());
        assertThrows(IllegalArgumentException.class,
                () -> s.stage("teacher", request(valid), new ByteArrayInputStream(new byte[]{1,2,3,5})));
        assertThrows(IllegalArgumentException.class,
                () -> s.stage("teacher", request(valid), new ByteArrayInputStream(new byte[]{1,2})));
        assertThrows(IllegalArgumentException.class,
                () -> s.stage("teacher", request(valid), new ByteArrayInputStream(new byte[]{1,2,3,4,5})));
        assertEquals(0, count.get());
    }

    @Test void wrongPlatformUnsafeNameAndOversizedArtifactFailClosed() throws Exception {
        byte[] payload = {1, 2};
        var good = request(payload);
        var count = new AtomicInteger();
        var s = new QuestLessonUploadService((who, org) -> true,
                (path, key) -> count.incrementAndGet());
        var invalid = new QuestLessonUploadService.Request(ORG, good.releaseId(),
                good.lessonId(), good.collectionId(), "Windows", "5.6",
                good.runtimeBuildId(), good.artifactName(), good.artifactSizeBytes(), good.sha256Hex());
        assertThrows(IllegalArgumentException.class,
                () -> s.stage("teacher", invalid, new ByteArrayInputStream(payload)));
        invalid = new QuestLessonUploadService.Request(ORG, good.releaseId(),
                good.lessonId(), good.collectionId(), "Android_ASTC", "5.6",
                good.runtimeBuildId(), "../evil.pak", good.artifactSizeBytes(), good.sha256Hex());
        assertThrows(IllegalArgumentException.class,
                () -> s.stage("teacher", invalid, new ByteArrayInputStream(payload)));
        invalid = new QuestLessonUploadService.Request(ORG, good.releaseId(),
                good.lessonId(), good.collectionId(), "Android_ASTC", "5.6",
                good.runtimeBuildId(), good.artifactName(),
                QuestLessonUploadService.MAX_ARTIFACT_BYTES + 1, good.sha256Hex());
        assertThrows(IllegalArgumentException.class,
                () -> s.stage("teacher", invalid, new ByteArrayInputStream(payload)));
        assertEquals(0, count.get());
    }
}
