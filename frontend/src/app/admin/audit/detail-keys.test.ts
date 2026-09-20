import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

/**
 * 감사 화면이 <b>안 들여다볼 칸을 실제로 일러 주나</b>(`Q135`).
 *
 * <p><b>왜 원문을 읽나.</b> {@code apiSession} 쪽 시험은 「일러 주면 그대로 온다」까지만 잰다 —
 * 이 화면이 <b>일러 주는 것을 잊으면</b> 그쪽은 초록인 채로 열쇠가 조용히 바뀐다.
 * 화면을 그려서 재려면 서버 컴포넌트를 돌려야 하는데 그 값이 이 한 줄보다 비싸다.
 * 같은 수를 {@code login/demo-accounts.test.ts} 가 시드 SQL 에 이미 쓴다.
 *
 * <p><b>무엇이 걸리나</b>: 칸을 빼거나, {@code OPAQUE} 를 안 넘기거나, 둘 다.
 * <b>무엇이 안 걸리나</b>: 서버가 {@code detail} 을 다른 이름으로 바꾸는 날 — 그건 응답 계약이
 * 바뀌는 것이라 백엔드 시험이 먼저 빨개진다.
 */
const SOURCE = readFileSync(resolve(import.meta.dirname, "page.tsx"), "utf8");

describe("감사 화면의 detail 열쇠", () => {
  it("안 들여다볼 칸으로 detail 을 든다", () => {
    expect(SOURCE).toMatch(/const OPAQUE = [[^]]*"detail"/);
  });

  it("그 목록을 부를 때 실제로 넘긴다", () => {
    expect(SOURCE).toMatch(/apiSession<AuditLogPage>([^)]*OPAQUE)/);
  });
});
