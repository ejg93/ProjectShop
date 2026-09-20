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
