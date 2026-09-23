import { afterEach, describe, expect, it, vi } from "vitest";

import { GET } from "./route";

afterEach(() => {
  vi.unstubAllEnvs();
});

/**
 * 프론트가 제 커밋을 말하나(`Q205`). `scripts/deploy-check.sh` 가 이 값으로 「그 커밋이 떴나」를 잰다.
 */
describe("판 정보", () => {
  it("Railway 가 넣은 커밋을 그대로 낸다", async () => {
    vi.stubEnv("RAILWAY_GIT_COMMIT_SHA", "523590d8f4f4e3cd3bc8808b167c8cdf7836dff3");

    expect(await GET().json()).toEqual({
      app: "shop-frontend",
      commit: "523590d8f4f4e3cd3bc8808b167c8cdf7836dff3",
    });
  });

  it("변수가 없으면 unknown — 스크립트가 그것을 빨강으로 읽는다", async () => {
    vi.stubEnv("RAILWAY_GIT_COMMIT_SHA", undefined);

    expect((await GET().json()).commit).toBe("unknown");
  });
});
