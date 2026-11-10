package academy.vrtraining.platform.licensing;

import academy.vrtraining.platform.licensing.LicenseContracts.*;
import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * PostgreSQL reference store; each multi-step write uses a transaction.
 * Production migration/connection pooling and IAM integration remain gated.
 */
public final class PostgresLicenseStore implements LicenseStore {
    private final String url, user, password;

    public PostgresLicenseStore(String url, String user, String password) {
        if (url == null || !url.startsWith("jdbc:postgresql://"))
            throw new IllegalArgumentException("PostgreSQL JDBC URL required");
        this.url = url;
        this.user = java.util.Objects.requireNonNull(user);
        this.password = java.util.Objects.requireNonNull(password);
    }

    private Connection open() throws SQLException { return DriverManager.getConnection(url, user, password); }
    private static IllegalStateException databaseError(SQLException e) {
        return new IllegalStateException("Organization licensing persistence failure", e);
    }
    private static Set<String> set(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        try {
            return Set.copyOf(Arrays.asList((String[]) array.getArray()));
        } finally { array.free(); }
    }
    private static Organization organization(ResultSet r) throws SQLException {
        return new Organization((UUID)r.getObject("organization_id"), r.getString("display_name"),
                r.getString("verified_contact_email"), r.getString("owner_subject"),
                r.getTimestamp("created_at").toInstant());
    }
    private static LicenseRequest requestRow(ResultSet r) throws SQLException {
        return new LicenseRequest((UUID)r.getObject("request_id"),
                (UUID)r.getObject("organization_id"), r.getString("requested_by_subject"),
                r.getInt("requested_device_limit"), set(r, "requested_collections"),
                RequestStatus.valueOf(r.getString("state")), r.getTimestamp("created_at").toInstant());
    }

    @Override public Organization createTrial(String owner, String email, String name, Instant now,
                                              Instant ends, int devices, Set<String> collections) {
        UUID organizationId = UUID.randomUUID(), licenseId = UUID.randomUUID();
        try (var db = open()) {
            db.setAutoCommit(false);
            try {
                try (var q = db.prepareStatement(
                        "INSERT INTO platform.organization_account(organization_id,owner_subject," +
                        "verified_contact_email,display_name,created_at) VALUES(?,?,?,?,?)")) {
                    q.setObject(1, organizationId);
                    q.setString(2, owner);
                    q.setString(3, email);
                    q.setString(4, name);
                    q.setTimestamp(5, Timestamp.from(now));
                    q.executeUpdate();
                }
                try (var q = db.prepareStatement(
                        "INSERT INTO platform.organization_license(license_id,organization_id,kind," +
                        "starts_at,expires_at,device_limit,allowed_collections,issued_by_subject,issued_at)" +
                        " VALUES(?,?,'TRIAL',?,?,?,?,?,?)")) {
                    q.setObject(1, licenseId);
                    q.setObject(2, organizationId);
                    q.setTimestamp(3, Timestamp.from(now));
                    q.setTimestamp(4, Timestamp.from(ends));
                    q.setInt(5, devices);
                    q.setArray(6, db.createArrayOf("text", collections.toArray(String[]::new)));
                    q.setString(7, "SYSTEM_TRIAL");
                    q.setTimestamp(8, Timestamp.from(now));
                    q.executeUpdate();
                }
                db.commit();
                return new Organization(organizationId, name, email, owner, now);
            } catch (Exception e) {
                db.rollback();
                if (e instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                    var existing = byOwner(owner);
                    if (existing.isPresent()) return existing.get();
                }
                throw e;
            }
        } catch (SQLException e) { throw databaseError(e); }
        catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("Trial transaction failed", e); }
    }

    @Override public Optional<Organization> byOwner(String ownerSubject) {
        try (var db = open();
             var q = db.prepareStatement(
                "SELECT * FROM platform.organization_account WHERE owner_subject=?")) {
            q.setString(1, ownerSubject);
            try (var r = q.executeQuery()) {
                return r.next() ? Optional.of(organization(r)) : Optional.empty();
            }
        } catch (SQLException e) { throw databaseError(e); }
    }

    @Override public Optional<Organization> organization(UUID id) {
        try (var db = open();
             var q = db.prepareStatement(
                "SELECT * FROM platform.organization_account WHERE organization_id=?")) {
            q.setObject(1, id);
            try (var r = q.executeQuery()) {
                return r.next() ? Optional.of(organization(r)) : Optional.empty();
            }
        } catch (SQLException e) { throw databaseError(e); }
    }

    @Override public LicenseRequest createRequest(UUID orgId, String owner, int devices,
                                                   Set<String> collections, Instant now) {
        UUID requestId = UUID.randomUUID();
        try (var db = open();
             var q = db.prepareStatement(
                "INSERT INTO platform.organization_license_request(request_id,organization_id," +
                "requested_by_subject,requested_device_limit,requested_collections,created_at)" +
                " SELECT ?,organization_id,?,?,?,? FROM platform.organization_account " +
                "WHERE organization_id=? AND owner_subject=? AND status='ACTIVE'")) {
            q.setObject(1, requestId);
            q.setString(2, owner);
            q.setInt(3, devices);
            q.setArray(4, db.createArrayOf("text", collections.toArray(String[]::new)));
            q.setTimestamp(5, Timestamp.from(now));
            q.setObject(6, orgId);
            q.setString(7, owner);
            if (q.executeUpdate() != 1)
                throw new SecurityException("Organization owner not authorized");
            return new LicenseRequest(requestId, orgId, owner, devices,
                    collections, RequestStatus.PENDING, now);
        } catch (SQLException e) { throw databaseError(e); }
    }

    @Override public Optional<LicenseRequest> request(UUID id) {
        try (var db = open();
             var q = db.prepareStatement(
                "SELECT * FROM platform.organization_license_request WHERE request_id=?")) {
            q.setObject(1, id);
            try (var r = q.executeQuery()) {
                return r.next() ? Optional.of(requestRow(r)) : Optional.empty();
            }
        } catch (SQLException e) { throw databaseError(e); }
    }

    @Override public License approve(UUID requestId, Instant from, Instant to, int devices,
                                     Set<String> collections, String admin) {
        try (var db = open()) {
            db.setAutoCommit(false);
            try {
                LicenseRequest request;
                try (var q = db.prepareStatement(
                        "SELECT * FROM platform.organization_license_request WHERE request_id=? FOR UPDATE")) {
                    q.setObject(1, requestId);
                    try (var r = q.executeQuery()) {
                        if (!r.next()) throw new SecurityException("Unknown request");
                        request = requestRow(r);
                    }
                }
                if (request.status() != RequestStatus.PENDING)
                    throw new IllegalStateException("Already approved or rejected");
                if (devices > request.requestedDevices()
                        || !request.collections().containsAll(collections))
                    throw new SecurityException("Commercial scope exceeds approved request");
                UUID licenseId = UUID.randomUUID();
                try (var q = db.prepareStatement(
                        "INSERT INTO platform.organization_license(license_id,organization_id,kind,request_id," +
                        "starts_at,expires_at,device_limit,allowed_collections,issued_by_subject,issued_at)" +
                        " VALUES(?,?,'COMMERCIAL',?,?,?,?,?,?,?)")) {
                    q.setObject(1, licenseId);
                    q.setObject(2, request.organizationId());
                    q.setObject(3, requestId);
                    q.setTimestamp(4, Timestamp.from(from));
                    q.setTimestamp(5, Timestamp.from(to));
                    q.setInt(6, devices);
                    q.setArray(7, db.createArrayOf("text", collections.toArray(String[]::new)));
                    q.setString(8, admin);
                    q.setTimestamp(9, Timestamp.from(from));
                    q.executeUpdate();
                }
                try (var q = db.prepareStatement(
                        "UPDATE platform.organization_license_request SET state='APPROVED'," +
                        "resolved_at=?,resolved_by_subject=? WHERE request_id=? AND state='PENDING'")) {
                    q.setTimestamp(1, Timestamp.from(from));
                    q.setString(2, admin);
                    q.setObject(3, requestId);
                    if (q.executeUpdate() != 1) throw new IllegalStateException("Request resolved concurrently");
                }
                db.commit();
                return new License(licenseId, request.organizationId(),
                        LicenseKind.COMMERCIAL, from, to, devices, collections, requestId);
            } catch (Exception e) {
                db.rollback();
                throw e;
            }
        } catch (SQLException e) { throw databaseError(e); }
        catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("Approval transaction failed", e); }
    }

    @Override public Entitlements entitlements(UUID organizationId, Instant now) {
        try (var db = open();
             var q = db.prepareStatement(
                "SELECT l.device_limit,l.allowed_collections,l.expires_at " +
                "FROM platform.organization_license l JOIN platform.organization_account o" +
                " ON o.organization_id=l.organization_id " +
                "WHERE l.organization_id=? AND o.status='ACTIVE' AND l.revoked_at IS NULL " +
                "AND l.starts_at<=? AND l.expires_at>? " +
                "ORDER BY CASE WHEN l.kind='COMMERCIAL' THEN 0 ELSE 1 END," +
                "l.expires_at DESC LIMIT 1")) {
            q.setObject(1, organizationId);
            q.setTimestamp(2, Timestamp.from(now));
            q.setTimestamp(3, Timestamp.from(now));
            try (var r = q.executeQuery()) {
                if (!r.next()) return new Entitlements(organizationId, false, 0, Set.of(), now);
                return new Entitlements(organizationId, true, r.getInt(1),
                        set(r, "allowed_collections"), r.getTimestamp(3).toInstant());
            }
        } catch (SQLException e) { throw databaseError(e); }
    }
}
