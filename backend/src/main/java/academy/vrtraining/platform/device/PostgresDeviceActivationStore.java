package academy.vrtraining.platform.device;

import academy.vrtraining.platform.device.DeviceActivationService.*;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Transactional PostgreSQL adapter for one-use device activation. */
public final class PostgresDeviceActivationStore implements DeviceActivationService.Store {
    private final String url, user, password;
    private final boolean enforceLicenseSeats;

    public PostgresDeviceActivationStore(String url, String user, String password) {
        this(url, user, password, false); // Legacy test adapter; runtime explicitly enforces seats.
    }

    public PostgresDeviceActivationStore(String url, String user, String password,
                                         boolean enforceLicenseSeats) {
        if (url == null || !url.startsWith("jdbc:postgresql://"))
            throw new IllegalArgumentException("Postgres connection required");
        this.url = url;
        this.user = java.util.Objects.requireNonNull(user);
        this.password = java.util.Objects.requireNonNull(password);
        this.enforceLicenseSeats = enforceLicenseSeats;
    }

    private java.sql.Connection connect() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }
    private static RuntimeException failed(Exception e) {
        return new IllegalStateException("Device activation persistence failed", e);
    }
    private static PublicKey decode(byte[] encoded) {
        try {
            return KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(encoded));
        } catch (Exception e) { throw failed(e); }
    }
    private static State state(ResultSet r, String name) throws SQLException {
        return State.valueOf(r.getString(name));
    }
    private static Pending fromRow(ResultSet r) throws SQLException {
        return new Pending((UUID)r.getObject("flow_id"), r.getBytes("code_digest"),
                r.getBytes("challenge"), decode(r.getBytes("public_key_spki")),
                r.getTimestamp("expires_at").toInstant(),
                r.getString("tenant_id"), state(r, "state"));
    }

    @Override public void insert(Pending p) {
        try (var db = connect();
             var q = db.prepareStatement(
                "INSERT INTO platform.device_activation_flow(flow_id,code_digest,challenge,public_key_spki,expires_at,state) VALUES(?,?,?,?,?,?)")) {
            q.setObject(1, p.flowId());
            q.setBytes(2, p.codeDigest());
            q.setBytes(3, p.challenge());
            q.setBytes(4, p.publicKey().getEncoded());
            q.setTimestamp(5, Timestamp.from(p.expiresAt()));
            q.setString(6, State.PENDING.name());
            q.executeUpdate();
        } catch (SQLException e) { throw failed(e); }
    }

    @Override public Pending findPending(UUID flowId) {
        try (var db = connect();
             var q = db.prepareStatement(
                "SELECT * FROM platform.device_activation_flow WHERE flow_id=?")) {
            q.setObject(1, flowId);
            try (var r = q.executeQuery()) { return r.next() ? fromRow(r) : null; }
        } catch (SQLException e) { throw failed(e); }
    }

    @Override public boolean approve(UUID id, byte[] codeDigest, String tenantId, Instant now) {
        try (var db = connect()) {
            db.setAutoCommit(false);
            try {
                Pending pending;
                int attempts;
                try (var q = db.prepareStatement(
                        "SELECT * FROM platform.device_activation_flow WHERE flow_id=? FOR UPDATE")) {
                    q.setObject(1, id);
                    try (var r = q.executeQuery()) {
                        if (!r.next()) { db.rollback(); return false; }
                        pending = fromRow(r);
                        attempts = r.getInt("unsuccessful_attempts");
                    }
                }
                if (pending.state() != State.PENDING || !now.isBefore(pending.expiresAt())
                        || attempts >= 10) { db.rollback(); return false; }
                boolean matches = MessageDigest.isEqual(pending.codeDigest(), codeDigest);
                if (matches) {
                    try (var q = db.prepareStatement(
                        "UPDATE platform.device_activation_flow SET state='APPROVED',tenant_id=?,approved_at=? WHERE flow_id=?")) {
                        q.setString(1, tenantId);
                        q.setTimestamp(2, Timestamp.from(now));
                        q.setObject(3, id);
                        q.executeUpdate();
                    }
                } else {
                    try (var q = db.prepareStatement(
                        "UPDATE platform.device_activation_flow SET unsuccessful_attempts=unsuccessful_attempts+1 WHERE flow_id=?")) {
                        q.setObject(1, id);
                        q.executeUpdate();
                    }
                }
                db.commit();
                return matches;
            } catch (Exception e) { db.rollback(); throw e; }
        } catch (Exception e) { throw failed(e); }
    }

    @Override public Device activate(UUID id, Instant now) {
        try (var db = connect()) {
            db.setAutoCommit(false);
            try {
                Pending p;
                try (var q = db.prepareStatement(
                        "SELECT * FROM platform.device_activation_flow WHERE flow_id=? FOR UPDATE")) {
                    q.setObject(1, id);
                    try (var r = q.executeQuery()) {
                        if (!r.next()) { db.rollback(); return null; }
                        p = fromRow(r);
                    }
                }
                if (p.state() != State.APPROVED || !now.isBefore(p.expiresAt())
                        || p.tenantId() == null) { db.rollback(); return null; }
                if (enforceLicenseSeats) requireAvailableLicensedSeat(db, p.tenantId(), now);
                UUID deviceId = UUID.randomUUID();
                try (var q = db.prepareStatement(
                        "INSERT INTO platform.registered_device(device_id,tenant_id,public_key_spki,state,activated_at) VALUES(?,?,?,'ACTIVE',?)")) {
                    q.setObject(1, deviceId);
                    q.setString(2, p.tenantId());
                    q.setBytes(3, p.publicKey().getEncoded());
                    q.setTimestamp(4, Timestamp.from(now));
                    q.executeUpdate();
                }
                try (var q = db.prepareStatement(
                        "UPDATE platform.device_activation_flow SET state='ACTIVE',activated_device_id=? WHERE flow_id=?")) {
                    q.setObject(1, deviceId);
                    q.setObject(2, id);
                    q.executeUpdate();
                }
                db.commit();
                return new Device(deviceId, p.tenantId(), p.publicKey(), State.ACTIVE);
            } catch (Exception e) { db.rollback(); throw e; }
        } catch (SQLException e) { throw failed(e); }
        catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw failed(e); }
    }

    /**
     * Lock organization BEFORE reading the active license and active-device
     * count. Every licensed activation uses this same lock, so concurrent
     * activations cannot oversubscribe the final seat.
     */
    private static void requireAvailableLicensedSeat(java.sql.Connection db,
                                                     String tenantId, Instant now) throws SQLException {
        UUID organizationId;
        try { organizationId = UUID.fromString(tenantId); }
        catch (IllegalArgumentException e) { throw new SecurityException("Invalid organization identity"); }
        try (var lock = db.prepareStatement(
                "SELECT organization_id FROM platform.organization_account " +
                "WHERE organization_id=? AND status='ACTIVE' FOR UPDATE")) {
            lock.setObject(1, organizationId);
            try (var row = lock.executeQuery()) {
                if (!row.next()) throw new SecurityException("Organization inactive or unknown");
            }
        }
        int seats;
        try (var license = db.prepareStatement(
                "SELECT device_limit FROM platform.organization_license " +
                "WHERE organization_id=? AND revoked_at IS NULL AND starts_at<=? AND expires_at>? " +
                "ORDER BY CASE WHEN kind='COMMERCIAL' THEN 0 ELSE 1 END, " +
                "expires_at DESC LIMIT 1")) {
            license.setObject(1, organizationId);
            license.setTimestamp(2, Timestamp.from(now));
            license.setTimestamp(3, Timestamp.from(now));
            try (var row = license.executeQuery()) {
                if (!row.next()) throw new SecurityException("No active device license");
                seats = row.getInt(1);
            }
        }
        try (var count = db.prepareStatement(
                "SELECT count(*) FROM platform.registered_device " +
                "WHERE tenant_id=? AND state='ACTIVE'")) {
            count.setString(1, organizationId.toString());
            try (var row = count.executeQuery()) {
                row.next();
                if (row.getLong(1) >= seats) throw new SecurityException("Device seat limit reached");
            }
        }
    }

    @Override public Device findDevice(UUID id) {
        try (var db = connect();
             var q = db.prepareStatement(
                    "SELECT device_id,tenant_id,public_key_spki,state FROM platform.registered_device WHERE device_id=?")) {
            q.setObject(1, id);
            try (var r = q.executeQuery()) {
                return r.next() ? new Device((UUID)r.getObject("device_id"),
                        r.getString("tenant_id"), decode(r.getBytes("public_key_spki")),
                        state(r,"state")) : null;
            }
        } catch (SQLException e) { throw failed(e); }
    }

    /**
     * Deterministic admission after downgrade. The first N registered ACTIVE
     * devices by activation time and UUID retain access. Surplus devices
     * remain registered but receive no online entitlement until seats expand
     * or an earlier device is revoked. This never trusts caller deviceId as
     * proof of possession.
     */
    @Override public boolean isDeviceWithinCurrentSeatLimit(UUID deviceId, Instant now) {
        if (deviceId == null || now == null) return false;
        String sql = """
            WITH chosen_license AS (
              SELECT l.organization_id, l.device_limit
              FROM platform.organization_license l
              JOIN platform.organization_account o
                ON o.organization_id=l.organization_id
              JOIN platform.registered_device target
                ON target.tenant_id=l.organization_id::text
              WHERE target.device_id=? AND target.state='ACTIVE'
                AND o.status='ACTIVE'
                AND l.revoked_at IS NULL AND l.starts_at<=? AND l.expires_at>?
              ORDER BY CASE WHEN l.kind='COMMERCIAL' THEN 0 ELSE 1 END,
                       l.expires_at DESC
              LIMIT 1
            ), ranked AS (
              SELECT d.device_id,
                row_number() OVER (ORDER BY d.activated_at, d.device_id) AS seat_number
              FROM platform.registered_device d
              JOIN chosen_license lic ON d.tenant_id=lic.organization_id::text
              WHERE d.state='ACTIVE'
            )
            SELECT EXISTS (
              SELECT 1 FROM ranked r CROSS JOIN chosen_license lic
              WHERE r.device_id=? AND r.seat_number<=lic.device_limit
            )
            """;
        try (var db = connect(); var q = db.prepareStatement(sql)) {
            q.setObject(1, deviceId);
            q.setTimestamp(2, Timestamp.from(now));
            q.setTimestamp(3, Timestamp.from(now));
            q.setObject(4, deviceId);
            try (var r = q.executeQuery()) {
                return r.next() && r.getBoolean(1);
            }
        } catch (SQLException e) { throw failed(e); }
    }

    @Override public boolean revoke(UUID id, String tenantId) {
        try (var db = connect();
             var q = db.prepareStatement(
                    "UPDATE platform.registered_device SET state='REVOKED',revoked_at=now() " +
                    "WHERE device_id=? AND tenant_id=? AND state='ACTIVE'")) {
            q.setObject(1, id);
            q.setString(2, tenantId);
            return q.executeUpdate() == 1;
        } catch (SQLException e) { throw failed(e); }
    }
}
