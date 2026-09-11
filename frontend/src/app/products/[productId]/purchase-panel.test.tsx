import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { api } from "@/lib/api";

import { PurchasePanel, type OptionGroup, type PublicSku } from "./purchase-panel";

vi.mock("@/lib/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api")>()),
  api: vi.fn(),
}));

const OPTIONS: OptionGroup[] = [
  {
    productOptionId: 1,
    name: "색상",
    values: [
      { productOptionValueId: 11, value: "검정" },
      { productOptionValueId: 12, value: "흰색" },
    ],
  },
];

const SKUS: PublicSku[] = [
  { skuId: 100, priceInclVat: 19_000, inStock: true, optionValueIds: [11] },
  { skuId: 101, priceInclVat: 21_000, inStock: false, optionValueIds: [12] },
];

const SHIPPING = 3_000;

beforeEach(() => {
  vi.mocked(api).mockClear();
  vi.mocked(api).mockResolvedValue(undefined);
});

afterEach(() => vi.restoreAllMocks());

const renderPanel = (skus: PublicSku[] = SKUS, options: OptionGroup[] = OPTIONS) =>
  render(<PurchasePanel options={options} skus={skus} shippingFee={SHIPPING} />);

const pick = (value: string) => fireEvent.click(screen.getByLabelText(value));

const addButton = () => screen.queryByRole("button", { name: /장바구니에 담기|담는 중/ });

/**
 * 상품 상세의 구매 자리가 무엇을 내미나(`Q20`).
 *
 * <p><b>이 화면의 판단은 「지금 무엇을 살 수 있나」다.</b> 옵션을 다 고르기 전, 조합이 없을 때,
 * 품절일 때가 각각 다른 답이고 <b>셋 다 「버튼을 안 그린다」로 뭉치면 이유를 못 읽는다.</b>
 */
describe("구매 자리", () => {
  describe("고르기 전에는", () => {
    it("무엇을 고르라는지 이름으로 말한다", () => {
      renderPanel();

      // 「옵션을 선택해 주세요」로 뭉치면 축이 여럿일 때 어느 것이 남았는지 모른다.
      expect(screen.getByText(/색상을 선택해 주세요/)).toBeInTheDocument();
    });

    it("담기 버튼이 아예 없다", () => {
      renderPanel();

      // 눌러 놓고 「고르세요」를 띄우면 왕복이 헛돈다. **그릴 것이 없는 쪽도 본다**(`D15`).
      expect(addButton()).not.toBeInTheDocument();
    });
  });

  describe("고른 뒤", () => {
    it("총액을 큰 글자로, 내역을 그 아래 둔다", () => {
      renderPanel();

      pick("검정");

      // 법이 막는 것은 「총금액 중 일부만 표시해서 유인」이다(`D2` R24, 제21조의2 1호).
      // 상품가만 크게 두면 규제 대상이 된 그 관행이 된다.
      expect(screen.getByText("22,000원")).toBeInTheDocument();
      expect(screen.getByText(/상품 19,000원 \+ 배송비 3,000원/)).toBeInTheDocument();
    });

    it("배송비가 0이면 무료배송이라 쓴다", () => {
      render(<PurchasePanel options={OPTIONS} skus={SKUS} shippingFee={0} />);

      pick("검정");

      // 「+ 배송비 0원」은 사실이지만 읽는 사람이 계산을 한 번 더 한다.
      expect(screen.getByText(/무료배송/)).toBeInTheDocument();
    });

    it("품절이면 담기 버튼을 안 그리고 이유를 적는다", () => {
      renderPanel();

      pick("흰색");

      expect(screen.getByText(/품절된 조합입니다/)).toBeInTheDocument();
      expect(addButton()).not.toBeInTheDocument();
    });

    it("파는 조합이 아니면 품절과 다르게 말한다", () => {
      // 셀러가 조합 일부만 등록할 수 있다. 「품절」이라 하면 기다리면 산다는 뜻이 된다.
      renderPanel([SKUS[0]]);

      pick("흰색");

      expect(screen.getByText(/선택하신 조합은 판매하지 않습니다/)).toBeInTheDocument();
      expect(addButton()).not.toBeInTheDocument();
    });
  });

  describe("담을 때", () => {
    it("고른 조합의 SKU 로 보낸다", async () => {
      renderPanel();

      pick("검정");
      fireEvent.click(addButton()!);

      // 조합 짝짓기가 틀리면 **다른 상품이 장바구니에 담기고 오류가 안 난다.**
      await waitFor(() =>
        expect(api).toHaveBeenCalledWith("/api/cart/items", {
          method: "POST",
          body: { skuId: 100, quantity: 1 },
        }),
      );
    });

    it("담은 뒤 결과를 소리로도 전한다", async () => {
      renderPanel();

      pick("검정");
      fireEvent.click(addButton()!);

      // 눈으로만 바뀌면 화면낭독기는 아무 말도 안 한다(`D20` 「동적으로 바뀌는 것」).
      await waitFor(() =>
        expect(screen.getByRole("status")).toHaveTextContent(/장바구니에 담았습니다/),
      );
    });

    it("조합을 다시 고르면 앞 결과를 지운다", async () => {
      renderPanel();

      pick("검정");
      fireEvent.click(addButton()!);
      await waitFor(() =>
        expect(screen.getByRole("status")).toHaveTextContent(/장바구니에 담았습니다/),
      );

      pick("흰색");

      // 앞 결과는 **지금 조합의 것이 아니다.** 남겨 두면 품절 조합을 담은 것처럼 보인다.
      expect(screen.getByRole("status")).toBeEmptyDOMElement();
    });
  });
});
