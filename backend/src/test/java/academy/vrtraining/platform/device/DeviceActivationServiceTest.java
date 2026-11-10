package academy.vrtraining.platform.device;

import static org.junit.jupiter.api.Assertions.*;
import java.security.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import academy.vrtraining.platform.device.DeviceActivationService.*;

class DeviceActivationServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-10T12:00:00Z"), ZoneOffset.UTC);
    private final InMemoryStore store = new InMemoryStore();
    private final AdministratorAuthorization administrator = new AdministratorAuthorization() {
        public boolean mayActivateDevice(String subject, String tenant) {
            return "teacher-1".equals(subject) && "school-1".equals(tenant);
        }
        public boolean mayRevokeDevice(String subject, String tenant) {
            return mayActivateDevice(subject, tenant);
        }
    };
    private final DeviceActivationService service =
            new DeviceActivationService(store, administrator, CLOCK, new SecureRandom());

    @Test void activationNeedsAdminConsentAndPrivateKeyProofAndCannotReplay() throws Exception {
        var deviceKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Activation flow = service.begin(deviceKey.getPublic());
        assertThrows(GeneralSecurityException.class,
                () -> service.approve("stranger", "school-1", flow.flowId(), flow.userCode()));
        assertThrows(GeneralSecurityException.class,
                () -> service.approve("teacher-1", "school-1", flow.flowId(), "AAAA-AAAA"));
        service.approve("teacher-1", "school-1", flow.flowId(), flow.userCode());
        assertThrows(GeneralSecurityException.class,
                () -> service.approve("teacher-1", "school-1", flow.flowId(), flow.userCode()));
        var wrong = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var bad = Signature.getInstance("Ed25519");
        bad.initSign(wrong.getPrivate());
        bad.update(DeviceActivationService.bindingMessage(flow.flowId(), flow.challenge()));
        assertThrows(GeneralSecurityException.class,
                () -> service.complete(flow.flowId(), bad.sign()));
        var signer = Signature.getInstance("Ed25519");
        signer.initSign(deviceKey.getPrivate());
        signer.update(DeviceActivationService.bindingMessage(flow.flowId(), flow.challenge()));
        Device active = service.complete(flow.flowId(), signer.sign());
        assertEquals(State.ACTIVE, active.state());
        assertEquals("school-1", active.tenantId());
        assertThrows(GeneralSecurityException.class, () -> service.complete(flow.flowId(), new byte[64]));
        service.revoke("teacher-1", "school-1", active.deviceId());
        assertEquals(State.REVOKED, store.findDevice(active.deviceId()).state());
    }

    @Test void unapprovedOrExpiredActivationIsDenied() throws Exception {
        var key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var flow = service.begin(key.getPublic());
        var signer = Signature.getInstance("Ed25519");
        signer.initSign(key.getPrivate());
        signer.update(DeviceActivationService.bindingMessage(flow.flowId(), flow.challenge()));
        assertThrows(GeneralSecurityException.class,
                () -> service.complete(flow.flowId(), signer.sign()));
    }

    private static final class InMemoryStore implements Store {
        final Map<UUID, Pending> pendings = new HashMap<>();
        final Map<UUID, Device> devices = new HashMap<>();
        final Map<UUID, UUID> byFlow = new HashMap<>();
        @Override public synchronized void insert(Pending p) { pendings.put(p.flowId(), p); }
        @Override public synchronized Pending findPending(UUID id) { return pendings.get(id); }
        @Override public synchronized boolean approve(UUID id, byte[] hash, String tenant, Instant now) {
            Pending p = pendings.get(id);
            if (p == null || p.state() != State.PENDING || !now.isBefore(p.expiresAt())
                    || !MessageDigest.isEqual(p.codeDigest(), hash)) return false;
            pendings.put(id, new Pending(id, p.codeDigest(), p.challenge(), p.publicKey(),
                    p.expiresAt(), tenant, State.APPROVED));
            return true;
        }
        @Override public synchronized Device activate(UUID id, Instant now) {
            Pending p = pendings.get(id);
            if (p == null || p.state() != State.APPROVED || !now.isBefore(p.expiresAt()))
                return null;
            Device d = new Device(UUID.randomUUID(), p.tenantId(), p.publicKey(), State.ACTIVE);
            devices.put(d.deviceId(), d);
            pendings.put(id, new Pending(id, p.codeDigest(), p.challenge(), p.publicKey(),
                    p.expiresAt(), p.tenantId(), State.ACTIVE));
            byFlow.put(id, d.deviceId());
            return d;
        }
        @Override public synchronized Device findDevice(UUID id) { return devices.get(id); }
        @Override public synchronized boolean revoke(UUID id, String tenant) {
            Device d = devices.get(id);
            if (d == null || !d.tenantId().equals(tenant) || d.state() != State.ACTIVE) return false;
            devices.put(id, new Device(id, tenant, d.publicKey(), State.REVOKED));
            return true;
        }
    }
}
