import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

import { ApiError, toCamel } from "./api";

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
 * 입구 둘이 <b>같은 방어를 들고 있나</b>(`Q155`).
 *
 * <p>`apiUpload` 는 `api()` 를 못 쓴다 — 그쪽이 본문을 `JSON.stringify` 로 굳혀서 파일이 `{}` 가 된다.
 * 그래서 규칙이 <b>한 벌이 아니라 두 벌</b>이고, <b>한쪽만 고치는 날 갈린다.</b>
 * 실제로 갈렸다 — `Q140` 이 낸 `apiUpload` 에 204 분기가 없어서 본문 없는 2xx 에 터지는 것을
 * 마무리 41차 리뷰가 잡았다. 사람이 잡은 것은 다음에도 사람이 잡아야 한다.
 *
 * <p><b>글자로 잰다.</b> 두 함수를 실제로 돌리려면 `fetch`·쿠키·`location` 을 다 흉내 내야 하는데,
 * 그렇게 재는 것은 <b>흉내가 맞나</b>를 같이 재게 된다. 여기서 묻는 것은 그것이 아니라
 * <b>같은 자리가 양쪽에 있나</b>다.
 *
 * <p><b>못 보는 것</b>: 자리는 있는데 <b>다르게 구는 것</b>. 예를 들어 한쪽이 401 에서 다른 곳으로
 * 보내면 이 대조는 통과한다. 그것까지 보려면 층이 달라진다(`D15`).
 */
describe("입구 둘이 같은 방어를 든다", () => {
  const source = readFileSync(resolve(process.cwd(), "src/lib/api.ts"), "utf8");

  /** 두 입구 모두에 있어야 하는 자리와, 없으면 무엇이 나는가 */
  const guards: [string, string][] = [
    ['credentials: "same-origin"', "세션 쿠키가 안 실려서 로그인만 조용히 안 된다"],
    ["CSRF_HEADER", "토큰이 안 실려서 쓰기가 전부 401 이다"],
    ["status === 401", "세션이 끊겼을 때 로그인으로 안 보내고 화면마다 다른 오류가 뜬다"],
    ["status === 204", "본문 없는 2xx 에 response.json() 이 그 자리에서 터진다"],
    ["toApiError", "오류가 ApiError 가 아니라서 화면의 슬러그 분기가 전부 기본 문구로 떨어진다"],
    ["toCamel", "응답 표기가 안 바뀌어서 화면이 snake_case 를 읽는다"],
  ];

  const bodyOf = (name: string) => {
    const start = source.indexOf(`export async function ${name}<T>(`);
    expect(start, `${name} 를 못 찾았다. 이름이 바뀌면 이 대조가 0자를 재고 통과한다`).toBeGreaterThan(-1);
    const next = source.indexOf("\nexport ", start + 1);
    return source.slice(start, next < 0 ? source.length : next);
  };

  const api = bodyOf("api");
  const upload = bodyOf("apiUpload");

  it.each(guards)("%s 가 두 입구에 다 있다", (guard, cost) => {
    const missing = [
      ["api()", api.includes(guard)] as const,
      ["apiUpload()", upload.includes(guard)] as const,
    ]
      .filter(([, present]) => !present)
      .map(([entry]) => entry);

    expect(missing, `한쪽에만 있다: ${missing.join(", ")} 에 없다. 그러면 ${cost}`).toEqual([]);
  });
});
