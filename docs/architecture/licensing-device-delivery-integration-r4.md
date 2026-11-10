# R4.1 — Organization license → Quest activation → R2 Download Grant

**Status:** backend integrated contract and PostgreSQL acceptance; *not* a
production-ready device authentication system. PR #8 extends R4 organization
registration. No native Quest/real RustFS acceptance is included.

## Canonical identity and device seat model

- `organizationId` is a UUID and is the stable account/license key.
- Legacy `registered_device.tenant_id` is a varchar. New licensed activations
  store the canonical **UUID.toString()** value; free-form school names cannot
  reserve licensed devices.
- `organization_license` contains TRIAL/COMMERCIAL, expiry, seat limit and
  enabled collections. Active Commercial wins over Trial.
- Quest activation: (1) unregistered Quest produces Ed25519 key and short
  code, (2) verified OIDC organization OWNER approves it, (3) Quest signs
  server challenge, (4) Postgres atomically checks organization license,
  serializes seat reservation by locking organization row, counts active
  devices and registers the device.
- Each registered ACTIVE device consumes one seat. Revoked devices do not.
  Device swap uses a revoke followed by a fresh activation. This does **not**
  delete on-device installed content; its next online checks deny access.
- During trial, two seats can be registered. If both are active, third
  activation is denied even under concurrent completion.
- Expired Trial or suspended organization cannot activate new devices.
- Existing device activation code logic has 10 failed-code attempts,
  SHA/HMAC pepper and one-use flows. It does not replace real OAuth
  Device Authorization. Never use a displayed activation code as a
  long-lived access credential.

## Implemented HTTP calls (opt-in)

`POST /api/v1/device-activations` (unregistered Quest, no JWT):
```json
{"publicKeySpkiBase64":"<base64-encoded Ed25519 X509-SPKI>"}
```
Returns short `userCode`, `flowId`, 32-byte challenge and expiry.

`POST /api/v1/organizations/me/device-activation-approvals` (OIDC OWNER):
```json
{"flowId":"<uuid>","userCode":"ABCD-EFGH"}
```
Derives organization ID from owner JWT subject and DB; does not take tenant
from body. An OWNER without an active license cannot approve activation.

`POST /api/v1/device-activations/{flowId}/complete` (unregistered Quest):
```json
{"signatureBase64":"<Ed25519 signature of VRTA-DEVICE-ACTIVATE-V1 message>"}
```
Returns only `deviceId`, `organizationId` and `state`. Does not
expose the JCA public key object or return an authenticated device bearer.

`DELETE /api/v1/organizations/me/devices/{deviceId}` (OIDC OWNER):
Revokes the device within its organization and frees a licensed seat.

Endpoints are **OFF** unless **both** `VR_DEVICE_ACTIVATION_ENABLED=true` and `VR_DEVICE_ACTIVATION_HTTP_ENABLED=true`. Do not enable the HTTP flag without production-grade edge rate limits, monitoring and abuse controls.
The activation runtime now also requires
`VR_LICENSING_PERSISTENCE_ENABLED=true`, because granting an unlicensed
device is no longer a permitted runtime configuration.

## R2 Download Grant integration

Existing endpoint is **reused, not reimplemented**:
`POST /api/v1/me/content/{contentId}/download-grants`.

With `VR_LICENSING_DOWNLOAD_AUTHORIZATION_ENABLED=true`:
- JWT subject must match the **registered organization OWNER** and
  `tenant_id` must equal the real organization UUID.
- Organization must be active and a license valid at the current instant.
- `PublishedContentCollectionPort` resolves *trusted published* content
  into its collection, and the collection must be in the license.
- Then existing R2 `DownloadGrantService` resolves compatible
  `PublishedReleasePort` and returns the established transport/grant DTO.
- No other teacher/organization member is authorized by this initial
  OWNER-only web policy. Add a role-scoped member port in a separate review.
- `PublishedContentCollectionPort` is **default deny (empty)**. In the
  absence of real Content Domain publication no Grant is created.
- This is an **OWNER web grant**. It is NOT yet a device grant: a Quest
  still needs a registered-key-verified OAuth sender-constrained token and
  revocation checks on every device-bound request.

Separately, `LicensedDeviceContentAccess` is a domain check for an already
cryptographically-verified `deviceId`. It verifies registered-device ACTIVE
state, organization active license and published collection membership. It is
**not** exposed as a device REST endpoint accepting arbitrary deviceId.

## Security and operational blockers

**P0 before deployment:**
1. Authorization server with OAuth 2.0 Device Authorization Grant (RFC 8628)
   and verifiable registered-device proof on subsequent requests (sender
   constrained JWT or mTLS/DPoP-style binding). This cannot be replaced by
   client-controlled UUID.
2. Real persisted content publication, signed manifest approval,
   `PublishedContentCollectionPort` and `PublishedReleasePort` adapters.
3. Independent online API rate limits for public activation start/complete,
   approval code guessing and account enumeration; request size caps and
   activation flow cleanup.
4. Atomic seat *reconciliation* when an active commercial license is
   downgraded (e.g. 5 to 2 seats). Current activation enforces the limit
   during registration, but already-active surplus devices are not
   automatically suspended on downgrade.
5. Consistent signed offline license for Quest and expiry/revocation window;
   online checks cannot immediately revoke a completely offline device.
6. OIDC provider and email verification, organization member/owner transfer,
   Platform Admin identity and audited device operations.
7. Liquibase-managed migrations and connection pooling. Reference JDBC
   `DriverManager` is not an operations-ready persistence layer.

**Tests in this PR:**
- Real PostgreSQL concurrent activation of the final Trial seat.
- Revocation releases a seat; expired Trial cannot activate.
- Registered ACTIVE device licensed for DEMO can access published DEMO,
  revoked device/unknown content is refused.
- Existing R2 `DownloadGrantService` accepts owner with active matching
  rights and rejects forged owner, other tenant, expired Trial and
  unpublished content.
- New device activation routes remain absent when disabled.

**Deployment recommendation:** keep flags OFF in production until all P0
requirements are explicitly qualified. Passing Java/Postgres tests qualifies
this backend integration slice, not the full VR app.
