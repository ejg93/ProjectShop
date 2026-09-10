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
export default defineConfig({
  testDir: "e2e",
  // 화면 테스트(vitest)와 섞이지 않게 확장자를 가른다.
  testMatch: /.*\.spec\.ts/,
  // 실패가 잦으면 그 자체가 신호다. 재시도로 덮지 않는다.
  retries: 0,
  // 스모크 하나뿐이라 병렬이 의미가 없고, 같은 계정을 여럿이 쓰면 세션이 엉킨다.
  workers: 1,
  reporter: [["list"], ["html", { open: "never" }]],
  use: {
    baseURL: "http://localhost:3000",
    // 실패했을 때만 남긴다. 초록일 때 쌓이면 아무도 안 본다.
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  webServer: {
    // `dev` 가 아니라 `start` 다. 개발 서버는 첫 요청에 컴파일해서 타임아웃이 흔들린다.
    command: "npm run start",
    url: "http://localhost:3000",
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
