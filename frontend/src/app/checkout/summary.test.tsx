import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { SellerIdentity } from "@/components/seller-identity";

import { Amounts, SellerGroup, type CartItem, type SellerGrouping } from "./summary";

/**
 * 청약 전에 보여야 하는 것이 주문서에서 사라지지 않나(`15-5`, `D2` R19).
 *
 * <p><b>이 자리가 강제 지점이다.</b> 제7조·제14조제2항이 청약 전 확인·정정 절차를 요구하고
 * 제13조제2항이 <b>배송료를 포함한</b> 가격 표시를 요구하는데, 지금까지 그 표시를 지켜 주는 것은
 * 화면 코드뿐이었다 — 주문서에서 항목 목록을 지워도 아무 테스트가 안 깨졌다(`15-4` 가 남긴 자리).
 *
 * <p>글자를 그대로 못박지 않는다. 못박는 것은 <b>무엇이 보이는가</b>다 —
 * 상품명·수량·항목 금액·배송비·상품 합계·결제하실 금액.
 */
const ITEM: CartItem = {
  cartItemId: 1,
  productName: "데모 티셔츠",
  optionLabel: "검정 / M",
  sellerId: 7,
  sellerName: "데모셀러",
  priceInclVat: 29000,
  quantity: 2,
  available: true,
  withdrawalRestrictionReason: null,
  supplyLeadDays: null,
};

const GROUP: SellerGrouping = { sellerId: 7, sellerName: "데모셀러", items: [ITEM] };

const SELLER: SellerIdentity = {
  sellerId: 7,
  name: "데모셀러",
  businessName: "데모상사",
  representativeName: "김대표",
  businessRegNo: "123-45-67890",
  address: "서울 강남구 테헤란로 1",
  phone: "02-0000-0000",
  email: "seller@test.local",
  mailOrderNo: "2026-서울강남-0001",
  mailOrderExemptReason: null,
  defaultShippingFee: 3000,
};

describe("주문서의 청약 전 표시", () => {
  it("항목마다 이름·옵션·수량·금액이 보인다", () => {
    render(
      <SellerGroup
        group={GROUP}
        seller={SELLER}
        line={{ sellerId: 7, itemsAmount: 58000, shippingFee: 3000 }}
      />,
    );

    const item = screen.getByRole("listitem");

    // 수량과 금액이 없으면 「무엇을 얼마에 사는가」를 청약 전에 확인할 수가 없다.
    expect(within(item).getByText(/데모 티셔츠/)).toBeInTheDocument();
    expect(within(item).getByText(/검정 \/ M/)).toBeInTheDocument();
    expect(within(item).getByText(/× 2개/)).toBeInTheDocument();
    expect(within(item).getByText("58,000원")).toBeInTheDocument();
  });

  it("배송비를 항목 금액과 갈라서 적는다", () => {
    render(
      <SellerGroup
        group={GROUP}
        seller={SELLER}
        line={{ sellerId: 7, itemsAmount: 58000, shippingFee: 3000 }}
      />,
    );

    // 합계에 녹여 버리면 제13조제2항이 요구하는 표시가 아니다.
    expect(screen.getByText("배송비")).toBeInTheDocument();
    expect(screen.getByText("3,000원")).toBeInTheDocument();
  });

  it("약정이 없으면 법정 3영업일을 말한다", () => {
    render(
      <SellerGroup
        group={GROUP}
        seller={SELLER}
        line={{ sellerId: 7, itemsAmount: 58000, shippingFee: 3000 }}
      />,
    );

    // 공급시기는 청약 전에 알아야 할 거래조건이다(제13조제2항 4호, `14c`).
    expect(screen.getByText(/3영업일 이내에 발송됩니다/)).toBeInTheDocument();
  });

  it("약정이 있으면 그 날수를 말한다", () => {
    const agreed: CartItem = { ...ITEM, supplyLeadDays: 7 };

    render(
      <SellerGroup
        group={{ ...GROUP, items: [agreed] }}
        seller={SELLER}
        line={{ sellerId: 7, itemsAmount: 58000, shippingFee: 3000 }}
      />,
    );

    expect(screen.getByText(/7영업일/)).toBeInTheDocument();
  });

  it("판매자 정보가 문서 안에 있다", () => {
    render(
      <SellerGroup
        group={GROUP}
        seller={SELLER}
        line={{ sellerId: 7, itemsAmount: 58000, shippingFee: 3000 }}
      />,
    );

    // 제20조제2항이 청약 전까지 신원 제공을 요구한다(`R1`). 접혀 있어도 문서 안이면 된다.
    expect(screen.getByText("123-45-67890")).toBeInTheDocument();
  });

  it("금액이 상품·배송비·총액 셋으로 갈린다", () => {
    render(<Amounts itemsTotal={58000} shippingTotal={3000} />);

    expect(screen.getByText("상품 금액")).toBeInTheDocument();
    expect(screen.getByText("58,000원")).toBeInTheDocument();
    expect(screen.getByText("배송비")).toBeInTheDocument();
    expect(screen.getByText("3,000원")).toBeInTheDocument();

    // 총액은 배송비를 포함한 값이다(제13조제2항, `14d`).
    expect(screen.getByText("결제하실 금액")).toBeInTheDocument();
    expect(screen.getByText("61,000원")).toBeInTheDocument();
  });
});
