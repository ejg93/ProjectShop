import { defineConfig } from "@playwright/test";

/**
 * 화면까지 관통하는 층(청크 `Q18`).
 *
 * jsdom 이 못 밟는 셋을 본다 — Next 의 rewrite 프록시, 세션 쿠키 왕복, CSRF 토큰.
 * `D15` 가 「그 셋이 얽힌 버그가 한 번이라도 나면 세운다」고 조건을 적어 뒀고,
 * `13-2` 에서 「로그인이 200 인데 화면이 안 넘어가는」 결함이 실제로 났다.
 *
 * **백엔드는 여기서 안 띄운다.** Gradle 기동이 느려서 밖에서 올린다 —
 * 로컬은 사람이, CI 는 `e2e.yml` 의 앞 step 이 올린다(`Q18-1`).
 */

/**
 * 띄울 포트(`Q137`). 기본은 3000 이고 `E2E_PORT` 로 옮긴다.
 *
 * <p><b>다른 것이 3000 을 쥐고 있을 때를 위한 것이다.</b> 한 기계에서 저장소를 여럿 굴리면
 * 그 포트가 이미 물려 있고, 그러면 이 층을 아예 못 돌린다 — 못 돌린 시험은 없는 시험이다.
 * CI 는 빈 기계라 기본값으로 돈다.
 */
const PORT = process.env.E2E_PORT ?? "3000";

export default defineConfig({
  testDir: "e2e",
  // 화면 테스트(vitest)와 섞이지 않게 확장자를 가른다.
  testMatch: /.*\.spec\.ts/,
  // 실패가 잦으면 그 자체가 신호다. 재시도로 덮지 않는다.
  retries: 0,
  // 파일이 셋이고 셋 다 시드 계정을 쓴다. 같은 계정을 여럿이 동시에 쓰면 세션이 엉킨다.
  workers: 1,
  reporter: [["list"], ["html", { open: "never" }]],
  use: {
    baseURL: `http://localhost:${PORT}`,
    // 실패했을 때만 남긴다. 초록일 때 쌓이면 아무도 안 본다.
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  webServer: {
    // `dev` 가 아니라 `start` 다. 개발 서버는 첫 요청에 컴파일해서 타임아웃이 흔들린다.
    command: `npm run start -- --port ${PORT}`,
    url: `http://localhost:${PORT}`,
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
