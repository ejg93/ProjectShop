import { describe, expect, it } from "vitest";

import { ApiError } from "./api";
import { firstBadField, placeErrors } from "./field-errors";

const FIELDS = ["receiverName", "postalCode", "cardNumber"] as const;

/** 서버가 내는 것과 같은 모양으로 만든다. `type` 은 여기서 안 본다 */
function validationFailed(errors: { field: string; message: string }[]): ApiError {
  return new ApiError(400, "tag:projectshop.example,2026:error:validation-failed",
                      "Validation failure", "trace", errors);
}

describe("서버가 지목한 칸 붙이기", () => {
  it("중첩 이름의 마지막 마디를 칸 이름으로 쓴다", () => {
    const placed = placeErrors(
      validationFailed([{ field: "shipping.postal_code", message: "숫자 5자리" }]),
      FIELDS,
    );

    // 폼이 배송지를 한 단계로 펴 놨다. 점 표기를 그대로 찾으면 아무 칸도 안 걸린다.
    expect(placed.byField.postalCode).toBe("숫자 5자리");
    expect(placed.rest).toEqual([]);
  });

  it("화면에 칸이 없는 것은 버리지 않고 따로 준다", () => {
    const placed = placeErrors(
      validationFailed([{ field: "cart_item_ids", message: "비어 있습니다" }]),
      FIELDS,
    );

    // 버리면 사용자는 「다시 확인해 주세요」만 보고 무엇을 확인할지 모른다(`13h`).
    expect(placed.byField).toEqual({});
    expect(placed.rest).toEqual(["비어 있습니다"]);
  });

  it("같은 칸에 여럿이 오면 첫 것만 쓴다", () => {
    const placed = placeErrors(
      validationFailed([
        { field: "shipping.postal_code", message: "먼저 온 것" },
        { field: "postal_code", message: "나중에 온 것" },
      ]),
      FIELDS,
    );

    expect(placed.byField.postalCode).toBe("먼저 온 것");
  });

  it("우리 오류가 아니면 아무것도 안 붙인다", () => {
    // 프록시가 못 붙었거나 서버가 죽으면 `ApiError` 가 아닌 것이 온다.
    expect(placeErrors(new Error("네트워크"), FIELDS)).toEqual({ byField: {}, rest: [] });
  });

  it("초점은 화면에서 위에 있는 칸으로 간다", () => {
    const placed = placeErrors(
      validationFailed([
        { field: "card_number", message: "카드" },
        { field: "shipping.receiver_name", message: "이름" },
      ]),
      FIELDS,
    );

    // 서버가 준 순서가 아니라 **목록 순서**다. 목록이 곧 화면 순서다.
    expect(firstBadField(placed, FIELDS)).toBe("receiverName");
  });
});

describe("목록 색인이 낀 이름", () => {
  const FLAT = ["priceInclVat", "stockCount"] as const;
  const ROWS = ["skus.0.priceInclVat", "skus.1.priceInclVat"] as const;

  it("줄을 안 가리는 폼은 마지막 마디로 붙는다", () => {
    // 옵션 없는 상품이 그 모양이다 — 조합이 하나뿐이라 화면이 펴서 그린다(`Q136`).
    const placed = placeErrors(
      validationFailed([{ field: "skus[0].price_incl_vat", message: "0 이상이어야 합니다" }]),
      FLAT,
    );

    expect(placed.byField.priceInclVat).toBe("0 이상이어야 합니다");
    expect(placed.rest).toEqual([]);
  });

  it("줄을 가리는 폼은 그 줄에 붙는다", () => {
    const placed = placeErrors(
      validationFailed([
        { field: "skus[0].price_incl_vat", message: "첫 줄" },
        { field: "skus[1].price_incl_vat", message: "둘째 줄" },
      ]),
      ROWS,
    );

    expect(placed.byField["skus.0.priceInclVat"]).toBe("첫 줄");
    expect(placed.byField["skus.1.priceInclVat"]).toBe("둘째 줄");
    expect(placed.rest).toEqual([]);
  });

  it("점 표기로 와도 같게 붙는다", () => {
    // 서버가 `skus[0]` 을 쓰든 `skus.0` 을 쓰든 화면이 달라지면 안 된다.
    const placed = placeErrors(
      validationFailed([{ field: "skus.0.price_incl_vat", message: "첫 줄" }]),
      ROWS,
    );

    expect(placed.byField["skus.0.priceInclVat"]).toBe("첫 줄");
  });

  it("줄이 여럿인데 폼이 한 칸이면 나머지를 안 버린다", () => {
    // 덮어쓰면 둘째 줄 사유가 아무 데도 안 남는다 — 그것이 이 청크가 막는 모양이다.
    const placed = placeErrors(
      validationFailed([
        { field: "skus[0].price_incl_vat", message: "첫 줄" },
        { field: "skus[1].price_incl_vat", message: "둘째 줄" },
      ]),
      FLAT,
    );

    expect(placed.byField.priceInclVat).toBe("첫 줄");
    expect(placed.rest).toEqual(["둘째 줄"]);
  });

  it("같은 경로가 두 번 오면 첫 것만 쓴다", () => {
    // 한 칸에 두 줄을 겹치면 칸 높이가 들쭉날쭉해지고, 하나를 고치면 보통 나머지도 풀린다.
    const placed = placeErrors(
      validationFailed([
        { field: "skus[0].price_incl_vat", message: "0 이상" },
        { field: "skus[0].price_incl_vat", message: "정수" },
      ]),
      FLAT,
    );

    expect(placed.byField.priceInclVat).toBe("0 이상");
    expect(placed.rest).toEqual([]);
  });

  it("중간 마디까지만 아는 폼에도 붙는다", () => {
    const placed = placeErrors(
      validationFailed([{ field: "substantiations[0].claim", message: "너무 깁니다" }]),
      ["substantiations.claim"] as const,
    );

    expect(placed.byField["substantiations.claim"]).toBe("너무 깁니다");
  });
});
