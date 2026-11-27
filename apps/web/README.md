# Hyperlooms Web

The Next.js application contains the public product site and an opt-in requester console at `/console`. The console uses OIDC Authorization Code + PKCE, encrypted HttpOnly sessions, a server-side API bridge, approved-recipe discovery, job submission/input staging/queueing, owner-scoped status and cancellation, and verified-result downloads. It is not a public signup or payment product. Provider onboarding and real GPU execution remain separately gated.

```powershell
pnpm install --frozen-lockfile
pnpm dev
pnpm check
pnpm build
```

Authoritative scheduling, trust, and accounting rules belong to the Spring Boot control plane, not this application.

## Requester console setup

The console needs the requester API, S3 storage, and a real OIDC issuer. For loopback development, use the [Keycloak profile](../../infrastructure/identity/README.md). Set `HYPERLOOMS_WEB_ORIGIN`, `HYPERLOOMS_WEB_API_ORIGIN`, `HYPERLOOMS_WEB_OIDC_ISSUER`, `HYPERLOOMS_WEB_OIDC_CLIENT_ID`, and a fresh 32-byte base64 `HYPERLOOMS_WEB_SESSION_KEY` in the web process. To generate the key in PowerShell: `[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))`. Use `HYPERLOOMS_WEB_ALLOW_INSECURE_LOCAL=true` only for literal localhost/127.0.0.1 HTTP development. The session expires with the access token; sign in again rather than storing a refresh token in the browser.

The local realm disables self-registration. An operator must create a test requester, approve the bounded recipe, and qualify a provider before a queued job can run. The console deliberately disables submission when no approved recipe exists. It accepts one `request.json` input and builds the canonical v1 manifest server-side. No browser receives S3 credentials or access tokens. The Spring API remains the authority for ownership, scopes and lifecycle decisions.

## UI design and behavior

The visual direction pairs a cinematic planet sunrise with a warm, editorial product page inspired by the typography of [Sarvam](https://www.sarvam.ai/). Hyperlooms has its own woven-network mark, an ivory/charcoal/burnt-orange palette, and locally bundled **DM Sans** and **Newsreader** variable fonts. These Fontsource packages use the SIL Open Font License; their license notices are included in the installed packages. Font loading does not call Google Fonts.

The hero background at `public/hero-horizon.png` was generated specifically for this project from the [hero art brief](hero-art-prompt.md). The screenshot reference supplied by the founder informed its planet-and-sunrise composition. All headline, navigation, status, and button text stays in HTML for accessibility and responsive layout. The fixed header changes from dark to light as the hero leaves view; a straight hairline separates the scene from the light content sections.

- `src/app/globals.css`: shared palette, typography, layouts, responsive breakpoints, focus states, and reduced-motion behavior.
- `src/components/loom-artwork.tsx`: original resolution-independent brand mark.
- `src/components/site-header.tsx`: responsive navigation, Escape dismissal, and focus return to the menu toggle.
- `src/components/network-experience.tsx`: builder/provider tabs with arrow-key, Home, and End navigation; every workflow is labeled as planned.
- `src/app/page.tsx`: server-rendered product narrative, actual Phase 0 milestones, native FAQ disclosures, and repository links.

The requester console adds client-side job refresh and form state. There are no fabricated capacity counters, testimonials, or active-job records. Do not remove the development disclosures until live host evidence exists.

## Browser checks

From `apps/web`, install Playwright Chromium once and run the suite:

```powershell
pnpm exec playwright install chromium
pnpm test:e2e
```

The test runner starts a local dev server on port 3100 and stops it afterwards. It checks desktop/mobile rendering, horizontal overflow, local font availability, anchor targets, tab interactions, FAQ interactions, mobile-menu dismissal, and automated WCAG A/AA rules for both role views. Automated accessibility tests are a baseline, not a claim of complete accessibility certification. Screenshots and failure traces are written to the git-ignored `test-results/` directory.

To check an already-running app using an installed Chrome browser:

```powershell
$env:PLAYWRIGHT_BASE_URL = "http://localhost:3000"
$env:PLAYWRIGHT_CHANNEL = "chrome"
pnpm test:e2e
```

`pnpm check` runs lint, TypeScript, unit tests, and formatting. Browser checks are separate because they require a browser installation. `pnpm build` validates the optimized production build.

Build the production container from the repository root so the workspace lockfile is available:

```powershell
docker build -f apps/web/Dockerfile -t hyperlooms-web:foundation .
```
