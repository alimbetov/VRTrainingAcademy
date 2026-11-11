# R5.3 — VRTrainingAcademy Content Studio (React/TypeScript)

Scope: scoped World→Area→Subject navigation and authoring, draft lesson cards,
three-locale title editing, taxonomy status and ordering, additional lesson
placements. UI is **not** a certification or release publisher.

Run: copy .env.example to .env.local and configure **real** OIDC authority/client;
`npm ci` after generating the reviewed lockfile (`npm install` initially),
`npm run dev`, `npm test`, `npm run build`.
Backend: Spring resource server with trusted issuer/audience; admin feature
`vr.content.admin.enabled=true` and enrolled PostgreSQL roles. Vite proxies
`/api` to localhost:8080 for development. Public OIDC code+PKCE client must
register `/auth/callback` redirect URI. No client secret in browser.

**Not implemented:** full production identity UX, admin membership provisioner,
collection management, lesson release publishing, physical Quest validation.
R5.4 upload panel must be enabled only after durable server-bound UploadSession
endpoint is available and independently qualified.
