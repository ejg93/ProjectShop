import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { expectNoAxeViolations } from "@/test/axe";

import { type AdminOrderDetail, OrderDetailView } from "./order-detail";

const ORDER: AdminOrderDetail = {
  orderNumber: "20260923-ABC123",
  status: "PAID",
  totalAmount: 10000,
  shippingFeeTotal: 3000,
  discountTotal: 0,
  payableAmount: 13000,
  createdAt: "2026-09-23T01:00:00Z",
  sellerOrders: [
    {
      sellerOrderNumber: "S-20260923-0001",
      sellerName: "조회셀러",
      status: "SHIPPING",
      shipOverdue: false,
      carrierCode: null,
      trackingNo: null,
      items: [{ orderItemId: 1, productName: "머그컵", optionLabel: null, quantity: 1, lineAmount: 10000 }],
    },
  ],
  history: [
    { sellerName: null, fromStatus: null, toStatus: "PAYMENT_PENDING", actorType: "USER", occurredAt: "2026-09-23T01:00:00Z" },
  ],
  shipping: {
    receiverName: "홍길동",
    receiverPhone: "010-0000-0000",
    postalCode: "06134",
    address1: "서울시 강남구",
    address2: "101호",
    deliveryMemo: null,
  },
  payment: {
    status: "APPROVED",
    approvalNumber: "A-1",
    cardIssuer: "국민",
    cardLast4: "1234",
    paidAt: "2026-09-23T01:01:00Z",
  },
};

/**
 * 관리자 주문 상세(`Q176`).
 *
 * <p><b>송장 없이 발송으로 옮겨진 묶음</b>(`57` 이 DB 에서 일부러 안 막았다)을 빈칸으로 두면 「아직 안 보냈다」로
 * 읽힌다. 그리고 <b>못 보는 칸은 키가 없다</b> — 감사자에게 결제 절이 그려지면 필드 그룹(`4d`)이 화면에서 샌다.
 */
describe("관리자 주문 상세", () => {
  it("보냈는데 송장이 없으면 「송장 없음」이라 적는다", () => {
    render(<OrderDetailView order={ORDER} />);

    expect(screen.getByText("송장 없음")).toBeInTheDocument();
  });

  it("결제 키가 없으면 결제 절을 안 그린다", () => {
    render(<OrderDetailView order={{ ...ORDER, payment: undefined }} />);

    expect(screen.queryByRole("heading", { name: "결제 정보" })).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "받는 분" })).toBeInTheDocument();
  });

  it("관리자에게는 결제 절이 선다", () => {
    render(<OrderDetailView order={ORDER} />);

    expect(screen.getByRole("heading", { name: "결제 정보" })).toBeInTheDocument();
    expect(screen.getByText("국민 ****1234")).toBeInTheDocument();
  });

  it("접근성 위반이 없다", async () => {
    const { container } = render(<OrderDetailView order={ORDER} />);

    await expectNoAxeViolations(container);
  });
});
