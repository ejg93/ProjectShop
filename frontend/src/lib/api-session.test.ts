import { afterEach, describe, expect, it, vi } from "vitest";

/**
 * 세션 입구가 <b>상태 코드마다 어디로 보내나</b>(`Q133`).
 *
 * <p><b>왜 여기에 두나.</b> 이 갈래는 화면이 아니라 입구에 있다 — 화면 테스트로는
 * 「이 화면이 403 을 잘 다루나」밖에 못 재고, 그 답은 화면 열일곱 개마다 다시 물어야 한다.
 * 입구를 재면 한 번으로 전부가 걸린다(`D15` 「어느 층에서 검증하나」).
 *
 * <p><b>`forbidden()` 을 흉내 내는 이유</b>는 진짜 것이 Next 런타임 안에서만 도는 신호라서다.
 * 우리가 고정하려는 것은 그 신호의 구현이 아니라 <b>403 에서 그것을 부르기로 한 판단</b>이다.
 */
const cookieJar = new Map<string, string>();

const forbidden = vi.fn(() => {
  throw new Error("FORBIDDEN_CALLED");
});
const redirect = vi.fn((to: string) => {
  throw new Error(`REDIRECT:${to}`);
});

vi.mock("next/headers", () => ({
  cookies: async () => ({
    get: (name: string) => {
      const value = cookieJar.get(name);
      return value === undefined ? undefined : { name, value };
    },
  }),
}));

vi.mock("next/navigation", () => ({
  forbidden: () => forbidden(),
  redirect: (to: string) => redirect(to),
}));

const { apiSession } = await import("./api-session");
const { ApiError } = await import("./api");

/** 백엔드가 이 상태와 본문으로 답한 것처럼 만든다 */
function answer(status: number, body: unknown = {}) {
  vi.spyOn(globalThis, "fetch").mockResolvedValue(
    new Response(JSON.stringify(body), {
      status,
      headers: { "Content-Type": "application/json" },
    }),
  );
}

afterEach(() => {
  vi.restoreAllMocks();
  forbidden.mockClear();
  redirect.mockClear();
  cookieJar.clear();
});

describe("apiSession", () => {
  it("403 이면 권한 없음 화면으로 간다", async () => {
    answer(403, { type: "urn:problem-type:audit-forbidden", detail: "권한이 없다" });

    await expect(apiSession("/api/admin/audit-logs")).rejects.toThrow("FORBIDDEN_CALLED");
    expect(forbidden).toHaveBeenCalledOnce();
  });

  it("403 을 오류로 안 던진다", async () => {
    answer(403, { type: "urn:problem-type:audit-forbidden", detail: "권한이 없다" });

    // 던지면 `error.tsx` 가 받고, 거기서는 「화면을 여는 데 실패했습니다 · 다시 시도」가 뜬다.
    // 고장이 아닌데 고장이라고 말하는 자리라 이 청크가 연 것이다.
    const thrown = await apiSession("/api/admin/audit-logs").catch((error: unknown) => error);
    expect(thrown).not.toBeInstanceOf(ApiError);
  });

  it("401 은 그대로 로그인으로 보낸다", async () => {
    answer(401);

    await expect(apiSession("/api/me")).rejects.toThrow("REDIRECT:/login?reason=login-required");
    expect(forbidden).not.toHaveBeenCalled();
  });

  it("404 는 부르는 화면이 잡게 던진다", async () => {
    // 「없다」와 「내 것이 아니다」를 가르는 규칙이 화면마다 달라서 입구가 안 정한다.
    answer(404, { type: "urn:problem-type:order-not-found", detail: "없다" });

    const thrown = await apiSession("/api/orders/nope").catch((error: unknown) => error);
    expect(thrown).toBeInstanceOf(ApiError);
    expect((thrown as InstanceType<typeof ApiError>).status).toBe(404);
    expect(forbidden).not.toHaveBeenCalled();
  });

  it("안 들여다볼 칸을 일러 주면 그 열쇠가 그대로 온다", async () => {
    // 감사 기록이 그 자리다. DB 에 적힌 글자가 곧 기록의 내용이라 바꾸면
    // 적힌 적 없는 것을 적혔다고 말하게 된다(`Q135`·`D16`).
    answer(200, { audit_log_id: 1, detail: { item_code: "A-1" } });

    const body = await apiSession<{ auditLogId: number; detail: Record<string, unknown> }>(
      "/api/audit-logs",
      ["detail"],
    );

    expect(body.auditLogId).toBe(1);
    expect(Object.keys(body.detail)).toEqual(["item_code"]);
  });

  it("안 일러 주면 중첩까지 바뀐다", async () => {
    // 기본값이 이것이라야 나머지 응답 열일곱 개가 지금 모양 그대로 돈다.
    answer(200, { audit_log_id: 1, detail: { item_code: "A-1" } });

    const body = await apiSession<{ detail: Record<string, unknown> }>("/api/audit-logs");

    expect(Object.keys(body.detail)).toEqual(["itemCode"]);
  });

  it("500 도 부르는 화면이 아니라 오류 경계로 간다", async () => {
    answer(500, { type: "urn:problem-type:internal", detail: "터졌다" });

    const thrown = await apiSession("/api/me").catch((error: unknown) => error);
    expect(thrown).toBeInstanceOf(ApiError);
    expect(forbidden).not.toHaveBeenCalled();
  });
});
