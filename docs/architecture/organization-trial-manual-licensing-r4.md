# DEC-LIC-01 — Self-service organization, 30-day Trial and manual license

Status: R4 Draft foundation; **no production entitlement rollout yet**.

## Product decision

1. User registers/signs into a trusted OIDC provider, confirms their **email**.
2. The authenticated owner sends their organization name to
   `POST /api/v1/organizations/register`. Backend derives the owner from
   verified JWT `sub`, `email`, `email_verified` — not the HTTP request.
3. PostgreSQL atomically creates the organization and a **one-time 30-day Trial**,
   initially allowing **2 VR devices** and **DEMO** collection.
4. Owner receives a stable `organizationId` (UUID). This is the account ID
   to include in a support email; **no password or token is emailed**.
5. For commercial use, owner creates a license request in the site or sends
   support its account ID, desired device count and collections.
6. A human platform administrator checks payment/contract and independently
   validates organization, request and collection publication.
7. An authorized `PLATFORM_ADMIN` approves the request through dedicated API.
   PostgreSQL locks the request and writes a COMMERCIAL license + resolves
   the request in **one transaction**. Replay/concurrent approvals fail.
8. Registered Quest stays associated with the organization. On next online
   entitlement refresh the new rights are applied; student accounts are never
   part of commercial licensing.

## Business invariants

- Trial is bound to a **verified owner OIDC subject**, not the email string;
  `UNIQUE(owner_subject)` and `UNIQUE(TRIAL organization)` prevent that
  identity from resetting the Trial on the same account.
- New OIDC subjects can otherwise generate repeat trials; company validation,
  rate limits and abuse controls are a **preproduction** requirement.
- Subscription and devices belong to **Organization**, not Teacher.
  Ownership transfer/member management is a separate IAM workflow.
- Trial expires at `startsAt + 30 * 24 hours`. Calendar-day behavior is
  intentionally not used; Instant UTC enforces exact duration.
- `startsAt <= now < expiresAt` is the effective license window.
- When Commercial and Trial overlap, active Commercial takes precedence.
  An expired commercial license grants **no rights** after expiration unless
  another valid license window (including an unexpired Trial) exists.
- Collection list stored on a commercial request is customer input, never a
  license authority. Before issuing, a **trusted published-catalog adapter**
  must approve every collection; default adapter is DENY.
- Manual approvals do not accept arbitrary `tenantId`, license ID or
  `adminSubject` from the caller. Authority comes from verified IAM identity.
- Device seats must be reserved **atomically** during device activation against
  current entitlement and active device count. The R4 foundation models the
  device limit but **does not yet implement seat enforcement**.
- Offline rights must be device-bound, signed, and time-limited. Online renewal
  observes license expiry/revocation. Exact offline window TBD and must be
  shorter than the commercial expiration/revocation tolerance.

## Current API (conditional on VR_LICENSING_PERSISTENCE_ENABLED=true)

`POST /api/v1/organizations/register`

```json
{"organizationName":"Example School"}
```

Verified OIDC JWT must supply `email` and `email_verified:true`.
No caller password or tenant ID accepted. Duplicate owner subject returns
the existing organization without extending Trial.

`GET /api/v1/organizations/me`

Authenticated owner receives their account UUID, verified contact and name.

`GET /api/v1/organizations/me/entitlements`

Returns `active`, `deviceLimit`, `collections`, `validUntil`.
This is an owner dashboard response, **NOT** a Quest license proof.

`POST /api/v1/organizations/me/license-requests`

```json
{"deviceLimit":5,"collections":["PHYSICS","CHEMISTRY"]}
```

Returns request UUID to include in support email.

`GET /api/v1/platform-admin/license-requests/{requestId}`

Only through trusted platform-admin role verification.

`POST /api/v1/platform-admin/license-requests/{requestId}/approve`

```json
{
  "deviceLimit":5,
  "collections":["PHYSICS"],
  "expiresAt":"2027-11-10T00:00:00Z"
}
```

May approve a *subset* of requested collections/device seats. A single
request can only be approved once. No billing gateway in MVP.

## Current technical implementation

- `LicenseContracts`, `LicenseStore`, `OrganizationLicensingService`.
- `PostgresLicenseStore`: transactionally inserted Trial, request, approval.
- `LicensingAuthorization`: platform-administrator trust boundary;
  default false, so no owner/teacher can create paid licenses.
- `LicensableCollectionCatalog`: published/licensable collection trust
  boundary; default false, so Commercial cannot be issued against an
  unconfigured catalog.
- `OrganizationLicensingController`: conditional REST routes; feature OFF by
  default; missing JWT rejected by global Spring Security resource server.
- `backend/db/migrations/R4__organization_trial_manual_licensing.sql`: manual
  reference schema, not production Liquibase.
- PostgreSQL 16 CI integration test: one-time Trial, owner scoping, admin
  isolation, issue idempotency and entitlement expiration.

## Required before production

P0:
1. Real OIDC organization owner/tenant membership + email verification.
2. Platform Administrator role from IAM (default DENY until installed).
3. Trusted Content Domain to validate commercial collection IDs and link
   `LicenseEntitlement` to actual published releases.
4. Atomic device seat reservation/replacement/revocation bound to org UUID
   and registered Quest key.
5. Wire license + device state into `DownloadEntitlementPort`, preserving
   the existing signed R1/R2 content delivery. Do not simply trust JWT tenant.
6. Tenant anti-abuse, account recovery, invitation/transfer authorization,
   high-rate request throttling and audit stream.
7. Actual admin portal and notification/email delivery; no backend mail sender
   has been created in R4.

P1:
8. Liquibase and JDBC pool, schema migration/recovery, metrics, monitoring,
   scheduled expiry/revocation and signable offline device leases.
9. Invoice/payment reconciliation and optional later billing gateway.
10. Data retention and secure deletion for account contacts and financial docs.

**Release qualification:** passing Java/PostgreSQL CI means the model/adapter
work, not that Quest licenses or payment/account verification work end-to-end.
