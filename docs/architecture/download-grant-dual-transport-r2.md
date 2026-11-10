# R2 — Backend-authorized download grants with two transports

Status: **backend contract and reference adapters**; no persistent release/entitlement
adapters exist yet, and no live RustFS endpoint has been configured.

## Decision DEC-DLV-07

All governance goes through Backend: browse, entitlement, tenant resolution,
release compatibility, grant issuance, audit and learning progress. Binary
transfers may bypass Backend **only** through short-lived scoped S3 presigned
GET URLs. The default is `DIRECT_RUSTFS`.

Use `BACKEND_PROXY` for networks where devices cannot reach the RustFS
origin or a separately deployed content CDN. Proxy streaming costs backend
bandwidth/CPU/connections; rate-limit, monitor, capacity-plan separately.

## Request path

1. User browses `GET /api/v1/public/catalog`; metadata only.
2. Client selects a specific lesson/content and platform.
3. Authenticated POST:
   `/api/v1/me/content/{contentId}/download-grants?platform=quest&runtimeBuildId=runtime-1`
   with Bearer JWT.
4. Spring Security validates JWT signature, issuer, expiry and audience.
   Tenant comes from the signed `tenant_id` claim, **never** request parameters.
5. `DownloadEntitlementPort` validates that subject + tenant may access content.
6. `PublishedReleasePort` resolves a **published immutable**, compatible release.
7. Backend sends release ID, manifest bytes (Base64), detached signature,
   expiration, per-artifact name, size, SHA-256 and transport URL.

For `DIRECT_RUSTFS`, URL is an AWS SDK S3 presigned GET (path-style,
RustFS endpoint, default 5 minute TTL, configurable 30–900s).

For `BACKEND_PROXY`, URL points to:
`GET /api/v1/me/content/{contentId}/artifacts/{artifactName}?platform=...&runtimeBuildId=...`
with Bearer JWT; every request and partial Range requires fresh authorization.
No caller-provided bucket or object key. S3 credentials stay on the server.

Never log presigned URL query strings.

## Fail-closed startup

The code includes `PublishedReleasePort` and `DownloadEntitlementPort`
interfaces with deny-all fallbacks. **This is deliberate:** until durable
publication, tenant, entitlement and device checks are implemented, no lesson
can receive a working download grant.

If OIDC issuer/audience are unset, JWT decoding rejects all bearer tokens.
Anonymous metadata catalog remains public.

The S3 signer/client are enabled only with:
`VR_DELIVERY_S3_ENABLED=true` and a valid HTTPS
`VR_DELIVERY_BASE_URL` (via Spring config below). They read credentials from
the AWS SDK DefaultCredentialsProvider, not from user devices.

## Config mapping to application.yml

```yaml
vr:
  auth:
    issuer-uri: ${VR_AUTH_ISSUER_URI:}
    audience: ${VR_AUTH_AUDIENCE:}
  delivery:
    transport: ${VR_DELIVERY_TRANSPORT:DIRECT_RUSTFS}
    s3-enabled: ${VR_DELIVERY_S3_ENABLED:false}
    base-url: ${VR_DELIVERY_BASE_URL:}
    region: ${VR_DELIVERY_REGION:us-east-1}
    grant-ttl-seconds: ${VR_DELIVERY_GRANT_TTL_SECONDS:300}
```

## Known limitations (release blockers)

- Tenant claim alone is insufficient for tenant switching in organizations:
  use an identity service to bind token subject, tenant membership and
  device activation. Every adapter must check identity and entitlement.
- No PostgreSQL content release, grant audit, access revocation/rotation,
  publisher, or real S3/RustFS integration.
- A presigned URL can be reused until expiry; this is a bounded bearer
  capability, not one-use. Choose short TTL and stable immutable objects.
- The proxy reauthorizes each GET but has no streaming rate limiting,
  concurrency quotas, anti-abuse or upstream cancellation metrics yet.
- The JVM local downloader currently accepts only the configured RustFS origin;
  native support for `BACKEND_PROXY` needs an authenticated same-origin
  client transport and URL refresh during partial resume.
- The S3 response and signed-manifest identities must be bound to the same
  published release and validated at publication time (not implemented).
- No proof of native Unreal/Quest package mount or real RustFS delivery.

## Acceptance gates

1. Catalog anonymous/metadata does not trigger binary transfers.
2. Anonymous grant = 401; missing tenant claim = 403;
   mismatched tenant/entitlement = 403; unpublished release = 404.
3. Direct grant presigns only the authorized artifact, TTL bounded.
4. Proxy rejects unauthorized requests, including Range; no user-supplied S3 key.
5. Test live RustFS with real presign + GET + Range + resume + checksum.
6. Real Windows packaged and physical Quest, verified offline reopening.
