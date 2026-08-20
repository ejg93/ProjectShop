import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";
import { fileURLToPath } from "node:url";

/**
 * 화면 테스트(`D15`, `Q9`).
 *
 * <p><b>고정하려는 것은 판단이지 그림이 아니다.</b> 화면이 서버 응답을 받아 무엇을 그릴지
 * 고르는 자리 — 오류 이름에 따른 문구, 상태에 따라 보이는 버튼, 조작 뒤의 갱신 — 이 여기 걸린다.
 * 여백과 색은 안 본다. 그건 바뀌라고 있는 것이고 테스트로 굳히면 고칠 때마다 빨개진다.
 *
 * <p><b>브라우저가 아니다.</b> jsdom 이라 프록시·세션 쿠키·CSRF 는 못 밟는다.
 * 그쪽은 백엔드의 {@code HttpFlowTest} 가 진짜 HTTP 로 보고, 화면까지 관통하는 것은
 * 아직 아무도 안 본다 — 그 층(E2E)을 둘지는 나중에 정한다.
 */
export default defineConfig({
  plugins: [react()],
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./vitest.setup.ts"],
    include: ["src/**/*.test.{ts,tsx}"],
  },
  resolve: {
    // `@/` 별칭은 tsconfig 에 있는데 Vitest 는 그것을 안 읽는다. 여기서 같은 값을 준다.
    alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) },
  },
});
