import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { api } from "@/lib/api";

import { CartLine, type CartItem } from "./cart-line";

vi.mock("@/lib/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api")>()),
  api: vi.fn(),
}));

const refresh = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh }) }));

const ITEM: CartItem = {
  cartItemId: 1,
  skuId: 100,
  productId: 7,
  productName: "티셔츠",
  optionLabel: "검정 / L",
  sellerId: 3,
  sellerName: "가게",
  priceInclVat: 19_000,
  shippingFee: 3_000,
  quantity: 2,
  available: true,
};

beforeEach(() => {
  vi.mocked(api).mockClear();
  vi.mocked(api).mockResolvedValue(undefined);
  refresh.mockClear();
});

afterEach(() => vi.restoreAllMocks());

const renderLine = (patch: Partial<CartItem> = {}) =>
  render(<CartLine item={{ ...ITEM, ...patch }} />);

const minus = () => screen.getByRole("button", { name: "수량 줄이기" });
const plus = () => screen.getByRole("button", { name: "수량 늘리기" });

/**
 * 장바구니 한 줄이 무엇을 막나(`Q20`).
 *
 * <p><b>이 화면의 판단은 경계값이다.</b> 1에서 더 줄이거나 상한에서 더 늘리면
 * 서버가 400 을 주는데, <b>그 왕복이 일어나는 것 자체가 결함</b>이라 화면이 먼저 막는다.
 */
describe("장바구니 줄", () => {
  describe("수량 경계에서", () => {
    it("1이면 더 줄일 수 없다", () => {
      renderLine({ quantity: 1 });

      // 0 으로 보내면 서버가 400 을 준다. 「빼기」는 따로 있다.
      expect(minus()).toBeDisabled();
    });

    it("1에서 줄이기를 눌러도 요청이 안 나간다", () => {
      renderLine({ quantity: 1 });

      fireEvent.click(minus());

      // **누르는 데까지 간다**(`D15`) — `disabled` 만 보면 그 속성이 사라져도 안 걸린다.
      expect(api).not.toHaveBeenCalled();
    });

    it("상한이면 더 늘릴 수 없다", () => {
      // 서버가 거는 상한과 같은 값이다(`CartService.MAX_QUANTITY`).
      renderLine({ quantity: 99 });

      expect(plus()).toBeDisabled();
    });

    it("상한에서 늘리기를 눌러도 요청이 안 나간다", () => {
      renderLine({ quantity: 99 });

      fireEvent.click(plus());

      expect(api).not.toHaveBeenCalled();
    });

    it("경계 사이에서는 둘 다 눌린다", () => {
      renderLine({ quantity: 2 });

      // **남는 쪽도 같이 본다**(`D15`) — 영영 안 눌려도 위 넷은 초록이다.
      expect(minus()).toBeEnabled();
      expect(plus()).toBeEnabled();
    });
  });

  describe("수량을 바꾸면", () => {
    it("바꾼 값을 그대로 보낸다", async () => {
      renderLine({ quantity: 2 });

      fireEvent.click(plus());

      await waitFor(() =>
        expect(api).toHaveBeenCalledWith("/api/cart/items/100", {
          method: "PUT",
          body: { quantity: 3 },
        }),
      );
    });

    it("화면이 값을 직접 안 고치고 서버에 다시 그리게 한다", async () => {
      renderLine({ quantity: 2 });

      fireEvent.click(plus());

      // 수량이 바뀌면 합계도 품절 여부도 바뀐다. 화면에서 다시 계산하면
      // **서버가 아는 값과 화면이 아는 값이 갈린다.**
      await waitFor(() => expect(refresh).toHaveBeenCalled());
      // 그리는 것은 서버 몫이라 이 줄의 숫자는 그대로다.
      expect(screen.getByText("2개")).toBeInTheDocument();
    });

    it("결과를 소리로도 전한다", async () => {
      renderLine({ quantity: 2 });

      fireEvent.click(plus());

      await waitFor(() =>
        expect(screen.getByRole("status")).toHaveTextContent(/수량을 3개로 바꿨습니다/),
      );
    });
  });

  describe("살 수 없는 줄은", () => {
    it("수량 조절을 안 그리고 이유를 글로 적는다", () => {
      renderLine({ available: false });

      // 색으로만 알리면 색을 못 보는 사람에게 아무 말도 안 한 것이다(`D20`).
      expect(screen.getByText(/지금 구매할 수 없습니다/)).toBeInTheDocument();
      expect(screen.queryByRole("button", { name: "수량 늘리기" })).not.toBeInTheDocument();
    });

    it("빼기는 남는다", () => {
      renderLine({ available: false });

      // 못 사는 줄을 못 빼면 장바구니가 영영 막힌다.
      expect(screen.getByRole("button", { name: /빼기|삭제|빼다/ })).toBeEnabled();
    });
  });

  describe("빼기는", () => {
    it("확인을 겹치지 않고 곧장 보낸다", async () => {
      const confirmSpy = vi.spyOn(window, "confirm").mockReturnValue(false);

      renderLine();
      fireEvent.click(screen.getByRole("button", { name: /빼기|삭제|빼다/ }));

      // 되돌릴 수 있는 것에 확인을 붙이면 확인이 흔해져서 읽지 않고 누른다(`D20`).
      await waitFor(() => expect(api).toHaveBeenCalledWith("/api/cart/items/100", { method: "DELETE" }));
      expect(confirmSpy).not.toHaveBeenCalled();
    });
  });
});
