import { expect, test } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";

test("renders the foundation honestly, without page errors or overflow", async ({
  page,
}, testInfo) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.goto("/");
  await page.evaluate(() => document.fonts.ready);
  await expect(page.getByRole("heading", { level: 1 })).toHaveText(
    "Big ideas.Shared power.",
  );
  await expect(
    page.getByText("No live network or remote execution yet."),
  ).toBeVisible();
  await expect(
    page.getByText("PLANNED EXPERIENCE", { exact: true }),
  ).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  expect(
    await page.evaluate(
      () =>
        document.fonts.check('16px "DM Sans Variable"') &&
        document.fonts.check('16px "Newsreader Variable"'),
    ),
  ).toBe(true);
  const links = await page
    .locator('a[href^="#"]')
    .evaluateAll((elements) =>
      elements.every((element) =>
        document.getElementById(element.getAttribute("href")!.slice(1)),
      ),
    );
  expect(links).toBe(true);
  expect(errors).toEqual([]);
  await page.screenshot({
    path: testInfo.outputPath("home.png"),
    fullPage: true,
  });
});

test("foundation status stays pinned while scrolling", async ({
  page,
  isMobile,
}) => {
  await page.goto("/");
  const status = page.locator(".floating-development-label");
  await expect(status).toHaveCSS("position", "fixed");
  const before = await status.boundingBox();
  if (isMobile) {
    const scrollCue = await page.locator(".hero-scroll").boundingBox();
    expect(scrollCue!.y + scrollCue!.height).toBeLessThan(before!.y);
  }
  await page.evaluate(() => {
    document.documentElement.style.scrollBehavior = "auto";
    window.scrollTo(0, 900);
  });
  await expect(status).toBeInViewport();
  const after = await status.boundingBox();
  expect(Math.abs(after!.y - before!.y)).toBeLessThan(1);
});

test("role tabs support click and keyboard navigation", async ({ page }) => {
  await page.goto("/");
  const builders = page.getByRole("tab", { name: "For builders" });
  const providers = page.getByRole("tab", { name: "For providers" });
  await providers.click();
  await expect(providers).toHaveAttribute("aria-selected", "true");
  await expect(page.getByRole("tabpanel")).toContainText("Your spare power.");
  await expect(page.getByRole("tabpanel")).toContainText(
    "Authenticated connection",
  );
  await providers.press("ArrowLeft");
  await expect(builders).toBeFocused();
  await expect(builders).toHaveAttribute("aria-selected", "true");
  await expect(page.getByRole("tabpanel")).toContainText("Bring the idea.");
  await builders.press("End");
  await expect(providers).toBeFocused();
  await providers.press("Home");
  await expect(builders).toBeFocused();
});

test("FAQ answers expand and collapse", async ({ page }) => {
  await page.goto("/");
  const question = page
    .locator("summary")
    .filter({ hasText: "Can I use or contribute a GPU today?" });
  const answer = page.getByText(
    "Not yet. Hyperlooms is in its foundation stage.",
    { exact: false },
  );
  await expect(answer).not.toBeVisible();
  await question.click();
  await expect(answer).toBeVisible();
  await question.press("Enter");
  await expect(answer).not.toBeVisible();
});

test("mobile navigation closes on Escape and on section selection", async ({
  page,
  isMobile,
}) => {
  test.skip(!isMobile, "Mobile-only disclosure navigation");
  await page.goto("/");
  const toggle = page.getByRole("button", { name: "Open navigation" });
  await toggle.click();
  await expect(page.getByRole("navigation")).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(toggle).toBeFocused();
  await expect(page.getByRole("navigation")).not.toBeVisible();
  await toggle.click();
  await page
    .getByRole("navigation")
    .getByRole("link", { name: "The network" })
    .click();
  await expect(page).toHaveURL(/#network$/);
  await expect(page.getByRole("navigation")).not.toBeVisible();
});

test("navigation follows the dark-to-light hero transition", async ({
  page,
}) => {
  await page.goto("/");
  const header = page.locator(".site-header");
  await expect(header).not.toHaveClass(/is-light/);
  await page.locator("#network").scrollIntoViewIfNeeded();
  await expect(header).toHaveClass(/is-light/);
  await expect(page.locator("#network")).toHaveCSS(
    "background-color",
    "rgba(0, 0, 0, 0)",
  );
  await page.evaluate(() => window.scrollTo(0, 0));
  await expect(header).not.toHaveClass(/is-light/);
});

test("no automated WCAG A/AA violations in either role view", async ({
  page,
}) => {
  await page.goto("/");
  for (const role of ["For builders", "For providers"]) {
    await page.getByRole("tab", { name: role }).click();
    const audit = await new AxeBuilder({ page })
      .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
      .analyze();
    expect(
      audit.violations.map(({ id, nodes }) => ({
        id,
        nodes: nodes.map(({ target, failureSummary }) => ({
          target,
          failureSummary,
        })),
      })),
    ).toEqual([]);
  }
});
