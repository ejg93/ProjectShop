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
/** 들어온 요청의 헤더(`Q240`). 비우면 손님 주소가 없는 요청이다 */
const requestHeaders = new Map<string, string>();

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
  headers: async () => ({ get: (name: string) => requestHeaders.get(name) ?? null }),
}));

vi.mock("next/navigation", () => ({
  forbidden: () => forbidden(),
  redirect: (to: string) => redirect(to),
}));

const { apiPublic, apiSession } = await import("./api-session");
const { ApiError } = await import("./api");

/**
 * 서버가 실제로 쓰는 오류 이름 접두어(`Q1`). <b>`urn:` 이 아니다</b> —
 * 다른 것을 쓰면 응답 모양이 아닌 것을 응답인 척 고정하게 된다.
 */
const ERROR_TYPE = "tag:projectshop.example,2026:error:";

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
  requestHeaders.clear();
});

/**
 * 손님 주소를 싣나(`Q240`). 안 실으면 서버 렌더 요청이 전부 Next 주소 하나로 요청 제한을 나눈다.
 *
 * <p>두 서버 입구를 같은 표로 돈다 — 한쪽만 싣는 날이 이 시험이 막는 자리다.
 */
describe("손님 주소", () => {
  const entries: [string, () => Promise<unknown>][] = [
    ["apiPublic()", () => apiPublic("/api/products")],
    ["apiSession()", () => apiSession("/api/me")],
  ];

  const sentHeaders = () => {
    const init = vi.mocked(globalThis.fetch).mock.calls[0][1] as RequestInit;
    return new Headers(init.headers);
  };

  it.each(entries)("%s 는 앞단이 채운 x-real-ip 를 x-forwarded-for 로 싣는다", async (_name, call) => {
    requestHeaders.set("x-real-ip", "198.51.100.7");
    answer(200, {});

    await call();

    expect(sentHeaders().get("x-forwarded-for")).toBe("198.51.100.7");
  });

  /**
   * <b>손님이 쓸 수 있는 헤더는 안 옮긴다</b>(마무리 55차 독립 리뷰). Next 는 `x-forwarded-for` 가 있으면 안 건드려서
   * 손님이 적은 값이 그대로 남고, 옮기면 백엔드가 그것을 손님 주소로 받아 요청마다 새 버킷이 열린다.
   */
  it.each(entries)("%s 는 들어온 x-forwarded-for 만 있으면 안 싣는다", async (_name, call) => {
    requestHeaders.set("x-forwarded-for", "203.0.113.99");
    answer(200, {});

    await call();

    expect(sentHeaders().has("x-forwarded-for")).toBe(false);
  });

  it.each(entries)("%s 는 둘 다 없으면 안 싣는다 — 빈 값을 지어내지 않는다", async (_name, call) => {
    answer(200, {});

    await call();

    expect(sentHeaders().has("x-forwarded-for")).toBe(false);
  });

  it("공개 입구는 손님 주소를 실어도 쿠키는 안 싣는다", async () => {
    cookieJar.set("SHOPSESSION", "s-1");
    requestHeaders.set("x-real-ip", "198.51.100.7");
    answer(200, {});

    await apiPublic("/api/products");

    expect(sentHeaders().has("cookie")).toBe(false);
  });
});

describe("apiSession", () => {
  it("403 이면 권한 없음 화면으로 간다", async () => {
    answer(403, { type: `${ERROR_TYPE}audit-forbidden`, detail: "권한이 없다" });

    await expect(apiSession("/api/admin/audit-logs")).rejects.toThrow("FORBIDDEN_CALLED");
    expect(forbidden).toHaveBeenCalledOnce();
  });

  it("403 을 오류로 안 던진다", async () => {
    answer(403, { type: `${ERROR_TYPE}audit-forbidden`, detail: "권한이 없다" });

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

  it("세션 쿠키가 있는데 401 이면 만료로 보낸다", async () => {
    // 쿠키가 있으면 로그인했던 사람이다 — 「로그인이 필요합니다」가 아니라 「세션이 끝났습니다」를 봐야 한다(`D24`·`D20`, `Q228`).
    cookieJar.set("SHOPSESSION", "stale");
    answer(401);

    await expect(apiSession("/api/me")).rejects.toThrow("REDIRECT:/login?reason=session-expired");
  });

  it("404 는 부르는 화면이 잡게 던진다", async () => {
    // 「없다」와 「내 것이 아니다」를 가르는 규칙이 화면마다 달라서 입구가 안 정한다.
    answer(404, { type: `${ERROR_TYPE}order-not-found`, detail: "없다" });

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
    answer(500, { type: `${ERROR_TYPE}internal`, detail: "터졌다" });

    const thrown = await apiSession("/api/me").catch((error: unknown) => error);
    expect(thrown).toBeInstanceOf(ApiError);
    expect(forbidden).not.toHaveBeenCalled();
  });
});
