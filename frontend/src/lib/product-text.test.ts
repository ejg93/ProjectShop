import { describe, expect, it } from "vitest";

import { productStatusHint, productStatusText } from "./product-text";

/**
 * 상품 상태가 셀러가 읽는 말로 나오나(`13f`).
 *
 * <p><b>눈으로는 못 밟는다.</b> 데모 데이터의 상품이 전부 판매 중이라
 * 검수 대기·판매 차단은 화면에 안 나온다 — 안 나오는 것은 테스트가 본다(`D15`).
 *
 * <p><b>서버가 상태를 늘려도 화면이 안 깨지는 것</b>이 여기서 확인하는 두 번째다.
 * `D5` 가 「모르는 열거값은 무시한다」고 정했고, 화면이 그것을 어기면
 * 상태를 하나 늘릴 때마다 프론트 배포를 기다리게 된다.
 */
describe("상품 상태 문구", () => {
  it("검수 단계가 셀러의 말로 나온다", () => {
    expect(productStatusText("pending_review")).toBe("검수 대기");
    expect(productStatusText("on_sale")).toBe("판매 중");
  });

  it("모르는 상태는 그대로 보여준다", () => {
    // 서버가 상태를 하나 늘려도 빈 칸이 되지 않는다(`D5`).
    expect(productStatusText("archived")).toBe("archived");
  });

  it("할 일이 갈리는 상태에는 안내가 붙는다", () => {
    // 「검수 대기」와 「판매 차단」은 둘 다 안 팔리는데 할 일이 정반대다.
    expect(productStatusHint("pending_review")).not.toBeNull();
    expect(productStatusHint("blocked")).not.toBeNull();
  });

  it("할 일이 없는 상태에는 안 붙는다", () => {
    // 「판매 중」에 안내를 붙이면 줄마다 글이 늘어서 밀도 7 이 무너진다(`D20`).
    expect(productStatusHint("on_sale")).toBeNull();
    expect(productStatusHint("sold_out")).toBeNull();
  });
});
