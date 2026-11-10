package academy.vrtraining.education.classroom;

import static org.junit.jupiter.api.Assertions.*;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OfflineSessionPermitTest {
    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test void validSignedPermitAllowsOnlyItsDeviceTenantAndRoster() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        UUID device = UUID.randomUUID(), student = UUID.randomUUID();
        var permit = new OfflineSessionPermit.Payload(UUID.randomUUID(), device, "school-1",
                "physics", "r2", NOW.minusSeconds(60), NOW.plusSeconds(3600),
                List.of(student, UUID.randomUUID()));
        var signed = OfflineSessionPermit.sign(permit, pair.getPrivate());
        OfflineSessionPermit.verify(permit, signed, pair.getPublic(), device, "school-1", CLOCK);
        OfflineSessionPermit.requireStudent(permit, student);
        assertThrows(GeneralSecurityException.class, () ->
                OfflineSessionPermit.requireStudent(permit, UUID.randomUUID()));
        assertThrows(GeneralSecurityException.class, () ->
                OfflineSessionPermit.verify(permit, signed, pair.getPublic(),
                        UUID.randomUUID(), "school-1", CLOCK));
        assertThrows(GeneralSecurityException.class, () ->
                OfflineSessionPermit.verify(permit, signed, pair.getPublic(),
                        device, "school-2", CLOCK));
    }

    @Test void forgedOrExpiredPermitCannotBeUsedOffline() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        UUID device = UUID.randomUUID(), student = UUID.randomUUID();
        var valid = new OfflineSessionPermit.Payload(UUID.randomUUID(), device, "school-1",
                "physics", "r1", NOW.minusSeconds(60), NOW.plusSeconds(60), List.of(student));
        var signature = OfflineSessionPermit.sign(valid, pair.getPrivate());
        var forged = new OfflineSessionPermit.Payload(valid.sessionId(), device, "school-1",
                "physics", "r2", valid.notBefore(), valid.expiresAt(), List.of(student));
        assertThrows(GeneralSecurityException.class, () ->
                OfflineSessionPermit.verify(forged, signature, pair.getPublic(),
                        device, "school-1", CLOCK));
        var expiredClock = Clock.fixed(NOW.plusSeconds(61), ZoneOffset.UTC);
        assertThrows(GeneralSecurityException.class, () ->
                OfflineSessionPermit.verify(valid, signature, pair.getPublic(),
                        device, "school-1", expiredClock));
    }

    @Test void rosterOrderIsCanonicallyStableButDuplicatesFail() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), device = UUID.randomUUID(), session = UUID.randomUUID();
        var x = new OfflineSessionPermit.Payload(session, device, "school-1", "physics", "r1",
                NOW, NOW.plusSeconds(3600), List.of(a,b));
        var y = new OfflineSessionPermit.Payload(session, device, "school-1", "physics", "r1",
                NOW, NOW.plusSeconds(3600), List.of(b,a));
        assertArrayEquals(OfflineSessionPermit.canonical(x), OfflineSessionPermit.canonical(y));
        assertThrows(IllegalArgumentException.class, () ->
                new OfflineSessionPermit.Payload(session, device, "school-1", "physics", "r1",
                        NOW, NOW.plusSeconds(3600), List.of(a,a)));
    }
}
