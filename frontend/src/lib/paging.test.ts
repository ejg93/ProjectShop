import { describe, expect, it } from "vitest";

import { lastPageOf, pageQuery } from "./paging";

describe("pageQuery", () => {
  it("화면의 쪽 번호를 건너뛸 행 수로 바꾼다", () => {
    expect(pageQuery(3, 20).toString()).toBe("offset=60&limit=20");
  });

  it("첫 쪽은 offset 0 이다", () => {
    expect(pageQuery(0, 50).toString()).toBe("offset=0&limit=50");
  });

  it("옛 이름 page·size 를 싣지 않는다", () => {
    const query = pageQuery(1, 20);
    expect(query.has("page")).toBe(false);
    expect(query.has("size")).toBe(false);
  });
});

describe("lastPageOf", () => {
  it("전체 개수와 limit 으로 마지막 쪽(0 기점)을 센다", () => {
    expect(lastPageOf({ total: 41, limit: 20 })).toBe(2);
    expect(lastPageOf({ total: 40, limit: 20 })).toBe(1);
  });

  it("비어 있으면 0 이다", () => {
    expect(lastPageOf({ total: 0, limit: 20 })).toBe(0);
  });

  // 배포 사이 창 — 새 화면이 옛 백엔드의 `page`·`size` 껍데기를 받으면 `limit` 이 없다
  it("limit 이 없는 옛 껍데기를 받아도 NaN 이 아니라 0 이다", () => {
    const legacy = { total: 41, page: 0, size: 20 } as unknown as { total: number; limit: number };
    expect(lastPageOf(legacy)).toBe(0);
  });
});
