import { expect, test } from "@playwright/test";

test("layout stays within the viewport from small phones to wide screens", async ({
  page,
  isMobile,
}, testInfo) => {
  test.skip(isMobile, "Viewport sweep uses the desktop browser context");
  await page.goto("/");
  await page.evaluate(() => document.fonts.ready);
  for (const width of [320, 375, 390, 600, 768, 801, 1024, 1280, 1440, 1920]) {
    await page.setViewportSize({ width, height: 900 });
    const overflow = await page.evaluate(() => {
      return Array.from(document.querySelectorAll("main *, header *, footer *"))
        .filter((element) => {
          const bounds = element.getBoundingClientRect();
          return (
            bounds.width > 0 &&
            (bounds.right > window.innerWidth + 1 || bounds.left < -1)
          );
        })
        .map((element) => `${element.tagName}.${element.className}`);
    });
    expect(overflow, `Overflow at ${width}px`).toEqual([]);
    if ([390, 768, 1440].includes(width)) {
      await page.evaluate(() => window.scrollTo(0, 0));
      await page.screenshot({ path: testInfo.outputPath(`hero-${width}.png`) });
      await page
        .locator("#network")
        .screenshot({ path: testInfo.outputPath(`network-${width}.png`) });
    }
  }
});
