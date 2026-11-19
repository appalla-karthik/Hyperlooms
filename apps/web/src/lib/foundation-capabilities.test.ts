import { describe, expect, it } from "vitest";

import {
  capabilityCount,
  foundationCapabilities,
} from "./foundation-capabilities";

describe("foundation capabilities", () => {
  it("reports the exact visible capability count", () => {
    expect(capabilityCount()).toBe(foundationCapabilities.length);
    expect(capabilityCount()).toBeGreaterThan(0);
  });
});
