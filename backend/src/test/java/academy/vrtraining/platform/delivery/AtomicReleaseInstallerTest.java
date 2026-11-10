package academy.vrtraining.platform.delivery;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicReleaseInstallerTest {
    @TempDir Path root;

    @Test void installsVerifiedReleaseAndAllowsIdempotentRetry() throws Exception {
        var fixture = fixture();
        Path installed = fixture.installer.install(fixture.release, fixture.signature,
                Map.of("world.pak", fixture.artifact));
        assertArrayEquals(Files.readAllBytes(fixture.artifact),
                Files.readAllBytes(installed.resolve("world.pak")));
        assertEquals(installed, fixture.installer.install(fixture.release, fixture.signature,
                Map.of("world.pak", fixture.artifact)));
    }

    @Test void refusesCorruptionWithoutPublishingDirectory() throws Exception {
        var fixture = fixture();
        Files.writeString(fixture.artifact, "tampered");
        assertThrows(Exception.class, () -> fixture.installer.install(fixture.release,
                fixture.signature, Map.of("world.pak", fixture.artifact)));
        assertFalse(Files.exists(root.resolve("installed/release-001")));
    }

    @Test void refusesWrongSignatureOrRuntime() throws Exception {
        var fixture = fixture();
        byte[] forged = fixture.signature.clone();
        forged[0] ^= 1;
        assertThrows(java.security.GeneralSecurityException.class,
                () -> fixture.installer.install(fixture.release, forged,
                        Map.of("world.pak", fixture.artifact)));
        var otherRuntime = new AtomicReleaseInstaller(root.resolve("other"),
                fixture.publicKey, "quest", "new-runtime");
        assertThrows(IllegalArgumentException.class,
                () -> otherRuntime.install(fixture.release, fixture.signature,
                        Map.of("world.pak", fixture.artifact)));
    }

    @Test void rejectsTraversalDuplicateAndUnexpectedFiles() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> new SignedReleaseVerifier.Artifact("../evil", 1, "0".repeat(64)));
        var fixture = fixture();
        assertThrows(java.security.GeneralSecurityException.class,
                () -> fixture.installer.install(fixture.release, fixture.signature,
                        Map.of("world.pak", fixture.artifact, "extra.bin", fixture.artifact)));
        assertThrows(IllegalArgumentException.class,
                () -> new SignedReleaseVerifier.Release("release", "module", "quest", "r1",
                        "entry", java.util.List.of(
                        new SignedReleaseVerifier.Artifact("a", 0, "0".repeat(64)),
                        new SignedReleaseVerifier.Artifact("a", 0, "0".repeat(64)))));
    }

    private Fixture fixture() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path artifact = root.resolve("world.pak");
        byte[] content = "fake cooked world payload".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(artifact, content);
        String sha = HexFormat.of().formatHex(java.security.MessageDigest
                .getInstance("SHA-256").digest(content));
        var release = new SignedReleaseVerifier.Release("release-001", "physics",
                "quest", "runtime-001", "PhysicsLab",
                java.util.List.of(new SignedReleaseVerifier.Artifact("world.pak",
                        content.length, sha)));
        var signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(SignedReleaseVerifier.canonicalBytes(release));
        return new Fixture(release, signer.sign(), artifact, pair.getPublic(),
                new AtomicReleaseInstaller(root.resolve("installed"), pair.getPublic(),
                        "quest", "runtime-001"));
    }

    private record Fixture(SignedReleaseVerifier.Release release, byte[] signature,
                           Path artifact, java.security.PublicKey publicKey,
                           AtomicReleaseInstaller installer) {}
}
