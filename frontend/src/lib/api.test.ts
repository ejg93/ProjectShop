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
