package academy.vrtraining.platform.delivery;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VRContentManagerTest {
    @TempDir Path root;

    @Test void downloadsFromMockRustFsRegistersAndReopensFromDurableInventory() throws Exception {
        byte[] payload = "synthetic Quest cooked-content fixture".getBytes(StandardCharsets.UTF_8);
        var server = mockRustFs(payload);
        try {
            URI url = baseUrl(server).resolve("/bucket/world.pak");
            var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            var fixture = signedRelease(pair, "release-1", payload);
            var manager = new VRContentManager(root.resolve("device"), baseUrl(server),
                    true, pair.getPublic(), "quest", "runtime-1", 0, 3);
            Path installed = manager.downloadAndInstall(fixture.release, fixture.signature,
                    Map.of("world.pak", url), Set.of());
            assertArrayEquals(payload, Files.readAllBytes(installed.resolve("world.pak")));
            assertEquals(1, manager.inventory().size());

            var reopened = new VRContentManager(root.resolve("device"), baseUrl(server),
                    true, pair.getPublic(), "quest", "runtime-1", 0, 3);
            assertEquals("release-1", reopened.inventory().getFirst().releaseId());
            reopened.markOpened("release-1");
            reopened.setPinned("release-1", true);
            assertTrue(reopened.inventory().getFirst().pinned());
        } finally { server.stop(0); }
    }

    @Test void atThreeSlotsFourthReplacesOnlyEligibleOldest() throws Exception {
        byte[] payload = "small".getBytes(StandardCharsets.UTF_8);
        var server = mockRustFs(payload);
        try {
            var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            var manager = new VRContentManager(root.resolve("device"), baseUrl(server),
                    true, pair.getPublic(), "quest", "runtime-1", 0, 3);
            URI url = baseUrl(server).resolve("/bucket/world.pak");
            for (int i = 1; i <= 3; i++) {
                var signed = signedRelease(pair, "release-" + i, payload);
                manager.downloadAndInstall(signed.release, signed.signature,
                        Map.of("world.pak", url), Set.of());
            }
            manager.setPinned("release-1", true);
            var fourth = signedRelease(pair, "release-4", payload);
            manager.downloadAndInstall(fourth.release, fourth.signature,
                    Map.of("world.pak", url), Set.of("release-2"));
            var ids = manager.inventory().stream().map(LocalReleaseRegistry.Entry::releaseId)
                    .collect(java.util.stream.Collectors.toSet());
            assertEquals(Set.of("release-1", "release-2", "release-4"), ids);
            assertFalse(Files.exists(root.resolve("device/installed/release-3")));
        } finally { server.stop(0); }
    }

    @Test void insufficientFreeSpaceDoesNotRemoveInstalledContent() throws Exception {
        byte[] payload = "small".getBytes(StandardCharsets.UTF_8);
        var server = mockRustFs(payload);
        try {
            var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            var manager = new VRContentManager(root.resolve("device"), baseUrl(server),
                    true, pair.getPublic(), "quest", "runtime-1", Long.MAX_VALUE, 3);
            var signed = signedRelease(pair, "release-1", payload);
            assertThrows(java.io.IOException.class, () -> manager.downloadAndInstall(
                    signed.release, signed.signature,
                    Map.of("world.pak", baseUrl(server).resolve("/bucket/world.pak")), Set.of()));
            assertTrue(manager.inventory().isEmpty());
        } finally { server.stop(0); }
    }

    @Test void restartCleansOrphanInstallerStagingWithoutTouchingLearnerFiles() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path device = root.resolve("device");
        Path orphan = device.resolve("installed/.staging-crash-001");
        Files.createDirectories(orphan);
        Files.writeString(orphan.resolve("world.pak"), "partial");
        Path progress = device.resolve("progress.sqlite");
        Files.writeString(progress, "progress survives");
        var manager = new VRContentManager(device, URI.create("https://rustfs.example.org"),
                false, pair.getPublic(), "quest", "runtime-1", 0, 3);
        assertTrue(manager.recoverInventory(Set.of()).isEmpty());
        assertFalse(Files.exists(orphan));
        assertEquals("progress survives", Files.readString(progress));
    }

    @Test void corruptedDownloadIsDiscardedAndCleanRetrySucceeds() throws Exception {
        byte[] expected = "GOOD".getBytes(StandardCharsets.UTF_8);
        var served = new java.util.concurrent.atomic.AtomicReference<>(
                "EVIL".getBytes(StandardCharsets.UTF_8));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/world.pak", exchange -> {
            byte[] bytes = served.get();
            exchange.getResponseHeaders().set("ETag", "\"asset-1\"");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            var signed = signedRelease(pair, "release-1", expected);
            var manager = new VRContentManager(root.resolve("device"), baseUrl(server),
                    true, pair.getPublic(), "quest", "runtime-1", 0, 3);
            URI url = baseUrl(server).resolve("/world.pak");
            assertThrows(java.security.GeneralSecurityException.class,
                    () -> manager.downloadAndInstall(signed.release, signed.signature,
                            Map.of("world.pak", url), Set.of()));
            assertFalse(Files.exists(root.resolve("device/downloads/release-1/world.pak.part")));
            assertTrue(manager.inventory().isEmpty());
            served.set(expected);
            var installed = manager.downloadAndInstall(signed.release, signed.signature,
                    Map.of("world.pak", url), Set.of());
            assertArrayEquals(expected, Files.readAllBytes(installed.resolve("world.pak")));
        } finally { server.stop(0); }
    }

    @Test void journalRecoveryCompletesInterruptedEvictionWithoutDeletingProgress() throws Exception {
        byte[] payload = "small".getBytes(StandardCharsets.UTF_8);
        var server = mockRustFs(payload);
        try {
            var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            Path local = root.resolve("device");
            var manager = new VRContentManager(local, baseUrl(server), true,
                    pair.getPublic(), "quest", "runtime-1", 0, 3);
            var signed = signedRelease(pair, "release-1", payload);
            manager.downloadAndInstall(signed.release, signed.signature,
                    Map.of("world.pak", baseUrl(server).resolve("/bucket/world.pak")), Set.of());
            Path journal = local.resolve(".evicting-release-1");
            Files.writeString(journal, "release-1");
            assertThrows(java.io.IOException.class,
                    () -> manager.recoverInventory(Set.of("release-1")));
            assertTrue(Files.exists(local.resolve("installed/release-1/world.pak")));
            assertTrue(manager.recoverInventory(Set.of()).isEmpty());
            assertFalse(Files.exists(local.resolve("installed/release-1")));
            assertFalse(Files.exists(journal));
        } finally { server.stop(0); }
    }

    private static HttpServer mockRustFs(byte[] content) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bucket/world.pak", exchange -> {
            exchange.getResponseHeaders().set("ETag", "\"fixture\"");
            exchange.sendResponseHeaders(200, content.length);
            try (var out = exchange.getResponseBody()) { out.write(content); }
        });
        server.start();
        return server;
    }

    private static URI baseUrl(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private record Signed(SignedReleaseVerifier.Release release, byte[] signature) {}
    private static Signed signedRelease(java.security.KeyPair pair, String id, byte[] content)
            throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        var release = new SignedReleaseVerifier.Release(id, "physics", "quest", "runtime-1",
                "entry", java.util.List.of(new SignedReleaseVerifier.Artifact(
                        "world.pak", content.length, hash)));
        var signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(SignedReleaseVerifier.canonicalBytes(release));
        return new Signed(release, signer.sign());
    }
}
