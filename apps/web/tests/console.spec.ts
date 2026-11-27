import { expect, test } from "@playwright/test";

test("requester console explains its gated state without leaking a fake job", async ({
  page,
}) => {
  await page.goto("/console");
  await expect(page.getByRole("heading", { level: 1 })).toContainText(
    "Submit work.",
  );
  await expect(page.getByText("CONTROLLED PREVIEW")).toBeVisible();
  await expect(page.getByText("No jobs yet.")).toHaveCount(0);
  await expect(
    page.getByText(
      /Console configuration is incomplete|Start with a verified account/,
    ),
  ).toBeVisible();
});
