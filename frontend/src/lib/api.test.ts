import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

import { ApiError, api, apiUpload, toCamel } from "./api";

/**
 * 오류 이름의 접두어가 <b>서버와 같나</b>(`Q1`, 축 6 재점검).
 *
 * <p>같은 문자열이 두 곳에 있다 — 서버의 {@code ErrorCode.TAG_PREFIX} 와 이 파일의
 * {@code ERROR_TYPE_PREFIX} 다. 언어가 달라서 <b>합칠 수가 없다.</b>
 *
 * <p><b>갈리면 조용하다.</b> 서버가 접두어를 바꾸면 화면은 슬러그를 못 뽑아
 * 전부 빈 문자열이 되고, 아홉 화면이 한꺼번에 기본 문구로 떨어진다 —
 * 오류가 나는 것이 아니라 <b>문구만 뭉개진다.</b> 그래서 여기서 대조한다.
 */
describe("오류 이름", () => {
  it("접두어가 서버의 ErrorCode 와 같다", () => {
    // 저장소 뿌리에서 센다. `import.meta.url` 로 올라가면 Vitest 가 파일 URL 을 안 줘서 깨진다.
    const java = readFileSync(
      resolve(process.cwd(), "../backend/src/main/java/com/projectshop/shop/error/ErrorCode.java"),
      "utf8",
    );

    const serverPrefix = /TAG_PREFIX = "([^"]+)"/.exec(java)?.[1];

    expect(serverPrefix, "ErrorCode 에서 TAG_PREFIX 를 못 찾았다").toBeTruthy();

    // 접두어를 직접 안 읽는다. 서버 값으로 만든 type 에서 슬러그가 떨어지는지로 본다 —
    // 그것이 화면이 실제로 기대는 동작이다.
    const error = new ApiError(422, `${serverPrefix}validation-failed`, "");

    expect(error.slug).toBe("validation-failed");
  });

  it("우리 것이 아닌 type 은 슬러그가 비어 있다", () => {
    // 프록시나 다른 서버가 낸 problem+json 이 우연히 우리 이름과 겹치지 않게 한다.
    expect(new ApiError(500, "about:blank", "").slug).toBe("");
  });
});

describe("표기 변환", () => {
  it("중첩까지 바꾼다", () => {
    expect(toCamel({ audit_log_id: 1, target_type: "sku" })).toEqual({
      auditLogId: 1,
      targetType: "sku",
    });
  });

  it("안 들여다볼 칸은 열쇠를 그대로 둔다", () => {
    // 감사 기록의 detail 이 그 자리다. DB 에 적힌 글자가 곧 기록의 내용이다(`Q135`·`D16`).
    const result = toCamel(
      { audit_log_id: 1, detail: { item_code: "A-1", seller_id: 2 } },
      ["detail"],
    ) as { auditLogId: number; detail: Record<string, unknown> };

    expect(result.auditLogId).toBe(1);
    expect(Object.keys(result.detail)).toEqual(["item_code", "seller_id"]);
  });

  it("안 들여다볼 칸도 이름 자체는 바뀐다", () => {
    // 그 칸이 든 것만 데이터고, 칸 이름은 우리 응답 계약이다.
    const result = toCamel({ raw_detail: { item_code: "A-1" } }, ["raw_detail"]) as Record<
      string,
      Record<string, unknown>
    >;

    expect(Object.keys(result)).toEqual(["rawDetail"]);
    expect(Object.keys(result.rawDetail)).toEqual(["item_code"]);
  });

  it("목록 안의 칸에도 걸린다", () => {
    // 감사 화면이 받는 모양이 목록이라, 여기서 안 걸리면 화면에서는 안 걸린 것과 같다.
    const result = toCamel({ items: [{ detail: { item_code: "A-1" } }] }, ["detail"]) as {
      items: { detail: Record<string, unknown> }[];
    };

    expect(Object.keys(result.items[0].detail)).toEqual(["item_code"]);
  });

  it("값은 안 건드린다", () => {
    // allowed_actions 의 값이 열거값이다. 바꾸면 화면이 서버가 모르는 이름으로 부른다.
    expect(toCamel({ allowed_actions: ["REQUEST_RETURN"] })).toEqual({
      allowedActions: ["REQUEST_RETURN"],
    });
  });
});

/**
 * 입구 둘이 <b>같은 방어를 드나</b>(`Q155` → `Q157`).
 *
 * <p>`apiUpload` 는 `api()` 를 못 쓴다 — 그쪽이 본문을 `JSON.stringify` 로 굳혀서 파일이 `{}` 가 된다.
 * <b>요청 쪽은 갈라야 하는 것이 맞다.</b> 그래서 아래 둘은 여전히 양쪽에 있어야 하고 글자로 잰다.
 *
 * <p><b>응답 쪽은 한 벌로 모았다</b>(`Q157`). 전에는 그것도 글자로 쟀는데,
 * 그 대조는 <b>자리는 있는데 다르게 구는 것</b>을 못 봤다 — 양쪽에 같은 문자열을 넣기만 하면
 * 통과했다. 이제 <b>실제로 불러서</b> 같은 응답에 같은 결과가 나오는지 본다.
 * 한쪽만 고치는 일이 성립하지 않으므로 이 시험이 빨개지는 길은 <b>공유 함수를 망가뜨리는 것</b>뿐이고,
 * 그때는 두 입구가 같이 빨갛다.
 */
describe("입구 둘이 같은 방어를 든다", () => {
  const source = readFileSync(resolve(process.cwd(), "src/lib/api.ts"), "utf8");

  /** 요청 쪽이라 양쪽에 있어야 하는 자리와, 없으면 무엇이 나는가 */
  const guards: [string, string][] = [
    ['credentials: "same-origin"', "세션 쿠키가 안 실려서 로그인만 조용히 안 된다"],
    ["CSRF_HEADER", "토큰이 안 실려서 쓰기가 전부 401 이다"],
  ];

  const bodyOf = (name: string) => {
    const start = source.indexOf(`export async function ${name}<T>(`);
    expect(start, `${name} 를 못 찾았다. 이름이 바뀌면 이 대조가 0자를 재고 통과한다`).toBeGreaterThan(-1);
    const next = source.indexOf("\nexport ", start + 1);
    return source.slice(start, next < 0 ? source.length : next);
  };

  it.each(guards)("%s 가 두 입구에 다 있다", (guard, cost) => {
    const missing = [
      ["api()", bodyOf("api").includes(guard)] as const,
      ["apiUpload()", bodyOf("apiUpload").includes(guard)] as const,
    ]
      .filter(([, present]) => !present)
      .map(([entry]) => entry);

    expect(missing, `한쪽에만 있다: ${missing.join(", ")} 에 없다. 그러면 ${cost}`).toEqual([]);
  });

  /**
   * 응답 쪽을 실제로 불러서 잰다.
   *
   * <p>두 입구를 같은 표로 돌린다 — <b>같은 응답에 같은 결과</b>가 나와야 하고,
   * 그것이 「한 벌이다」의 뜻이다.
   */
  describe("응답 처리가 한 벌이다", () => {
    const entries: [string, (path: string) => Promise<unknown>][] = [
      ["api()", (path) => api(path)],
      ["apiUpload()", (path) => apiUpload(path, new File(["x"], "x.png", { type: "image/png" }))],
    ];

    beforeEach(() => {
      // CSRF 토큰을 읽는 자리. 없으면 그 요청이 먼저 터져서 응답 쪽을 못 본다.
      document.cookie = "XSRF-TOKEN=test-token";
    });

    afterEach(() => {
      vi.unstubAllGlobals();
    });

    const respondWith = (response: Response) => {
      vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response));
    };

    it.each(entries)("%s 는 204 에 undefined 다", async (_name, call) => {
      respondWith(new Response(null, { status: 204 }));

      await expect(call("/api/things/1")).resolves.toBeUndefined();
    });

    it.each(entries)("%s 는 본문 표기를 바꾼다", async (_name, call) => {
      respondWith(
        new Response(JSON.stringify({ audit_log_id: 7 }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }),
      );

      await expect(call("/api/things")).resolves.toEqual({ auditLogId: 7 });
    });

    it.each(entries)("%s 는 오류를 ApiError 로 던진다", async (_name, call) => {
      respondWith(
        new Response(JSON.stringify({ type: "urn:problem-type:shop:not-found", title: "없다" }), {
          status: 404,
          headers: { "Content-Type": "application/problem+json" },
        }),
      );

      await expect(call("/api/things/9")).rejects.toBeInstanceOf(ApiError);
    });
  });
});
