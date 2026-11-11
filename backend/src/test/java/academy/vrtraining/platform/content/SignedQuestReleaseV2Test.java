package academy.vrtraining.platform.content;

import static org.junit.jupiter.api.Assertions.*;

import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class SignedQuestReleaseV2Test {
    private static SignedQuestReleaseV2.Release release() {
        return new SignedQuestReleaseV2.Release(
                "PHY-001-quest-1.0.0", "PHY-001", "PHYSICS_BASIC", "Android_ASTC",
                "5.6", "0.1.0-foundation", "/Game/Lessons/Physics/Maps/L_Electricity",
                List.of(new SignedQuestReleaseV2.Artifact(
                        "pakchunk1001-Android_ASTC.pak", 125000000L, "a".repeat(64))));
    }

    @Test void uploadedMetadataProducesTheSameCanonicalReleaseAsUnrealFixture() throws Exception {
        var input = new QuestLessonUploadService.Request(
                java.util.UUID.fromString("a75f8512-6e42-4741-ae7d-1e2a1eb0ea21"),
                "PHY-001-quest-1.0.0", "PHY-001", "PHYSICS_BASIC", "Android_ASTC",
                "5.6", "0.1.0-foundation", "/Game/Lessons/Physics/Maps/L_Electricity",
                "pakchunk1001-Android_ASTC.pak", 125000000L, "a".repeat(64));
        var candidate = SignedQuestReleaseV2.fromUpload(input);
        assertArrayEquals(SignedQuestReleaseV2.canonicalBytes(release()),
                SignedQuestReleaseV2.canonicalBytes(candidate));
    }

    @Test void canonicalEncodingMatchesIndependentPythonVector() throws Exception {
        var digest = java.security.MessageDigest.getInstance("SHA-256").digest(
                SignedQuestReleaseV2.canonicalBytes(release()));
        assertEquals("18ff9138abf0831c7ffd2a33f0281b1cea2eb195e7b4469c443ad718332ae290",
                HexFormat.of().formatHex(digest));
    }

    @Test void verifySignatureBindsEveryRuntimeField() throws Exception {
        var key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var signer = Signature.getInstance("Ed25519");
        signer.initSign(key.getPrivate());
        signer.update(SignedQuestReleaseV2.canonicalBytes(release()));
        byte[] signed = signer.sign();
        assertDoesNotThrow(() -> SignedQuestReleaseV2.verifySignature(release(), signed, key.getPublic()));
        var wrongLevel = new SignedQuestReleaseV2.Release(
                release().releaseId(), release().lessonId(), release().collectionId(),
                release().platform(), release().engineVersion(), release().runtimeBuildId(),
                "/Game/Lessons/Physics/Maps/L_Forged", release().artifacts());
        assertThrows(java.security.GeneralSecurityException.class,
                () -> SignedQuestReleaseV2.verifySignature(wrongLevel, signed, key.getPublic()));
        assertThrows(IllegalArgumentException.class,
                () -> SignedQuestReleaseV2.verifyRuntime(release(),
                        "0.1.1-new-runtime", "5.6", "Android_ASTC"));
        assertDoesNotThrow(() -> SignedQuestReleaseV2.verifyRuntime(release(),
                "0.1.0-foundation", "5.6", "Android_ASTC"));
    }

    @Test void duplicateArtifactAndEscapedEntryLevelRejected() {
        var r = release();
        assertThrows(IllegalArgumentException.class, () -> new SignedQuestReleaseV2.Release(
                r.releaseId(), r.lessonId(), r.collectionId(), r.platform(), r.engineVersion(),
                r.runtimeBuildId(), "/Game/Lessons/../Private", r.artifacts()));
        assertThrows(IllegalArgumentException.class, () -> new SignedQuestReleaseV2.Release(
                r.releaseId(), r.lessonId(), r.collectionId(), r.platform(), r.engineVersion(),
                r.runtimeBuildId(), r.entryLevel(), List.of(r.artifacts().getFirst(), r.artifacts().getFirst())));
    }

    @Test void artifactDigestDetectsTampering() throws Exception {
        byte[] original = {10, 20, 30};
        var digest = java.security.MessageDigest.getInstance("SHA-256").digest(original);
        var art = new SignedQuestReleaseV2.Artifact("smoke.pak", 3,
                HexFormat.of().formatHex(digest));
        assertDoesNotThrow(() -> SignedQuestReleaseV2.verifyArtifactDigest(art, original));
        assertThrows(java.security.GeneralSecurityException.class,
                () -> SignedQuestReleaseV2.verifyArtifactDigest(art, new byte[]{10, 20, 31}));
    }
}
