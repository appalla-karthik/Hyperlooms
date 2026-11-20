# HYPER_LOOMS Web

The Next.js application is the future browser surface for builders, providers, and administrators. Today it is a responsive product and foundation-status website, not a live compute dashboard. This UI redesign does not implement Phase 1, provider onboarding, authentication, payments, scheduling, or remote GPU execution.

```powershell
pnpm install --frozen-lockfile
pnpm dev
pnpm check
pnpm build
```

Authoritative scheduling, trust, and accounting rules belong to the Spring Boot control plane, not this application.

## UI design and behavior

The visual direction pairs a cinematic planet sunrise with a warm, editorial product page inspired by the typography of [Sarvam](https://www.sarvam.ai/). HYPER_LOOMS has its own woven-network mark, an ivory/charcoal/burnt-orange palette, and locally bundled **DM Sans** and **Newsreader** variable fonts. These Fontsource packages use the SIL Open Font License; their license notices are included in the installed packages. Font loading does not call Google Fonts.

The hero background at `public/hero-horizon.png` was generated specifically for this project from the [hero art brief](hero-art-prompt.md). The screenshot reference supplied by the founder informed its planet-and-sunrise composition. All headline, navigation, status, and button text stays in HTML for accessibility and responsive layout. The fixed header changes from dark to light as the hero leaves view; a straight hairline separates the scene from the light content sections.

- `src/app/globals.css`: shared palette, typography, layouts, responsive breakpoints, focus states, and reduced-motion behavior.
- `src/components/loom-artwork.tsx`: original resolution-independent brand mark.
- `src/components/site-header.tsx`: responsive navigation, Escape dismissal, and focus return to the menu toggle.
- `src/components/network-experience.tsx`: builder/provider tabs with arrow-key, Home, and End navigation; every workflow is labeled as planned.
- `src/app/page.tsx`: server-rendered product narrative, actual Phase 0 milestones, native FAQ disclosures, and repository links.

Only the navigation and role tabs require client-side React. There are no fabricated capacity counters, testimonials, active-job records, or signup forms. The repository is the current call to action. Do not remove the development disclosures until the corresponding backend behavior exists.

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
