import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { expectNoAxeViolations } from "@/test/axe";

import { type AdminOrderDetail, OrderDetailView } from "./order-detail";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: () => {} }) }));

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
      forcibleStatuses: ["DELIVERED", "CANCELLED"],
      allowedActions: [],
      rejectionReasons: [],
      returnRequest: null,
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

  it("반품 판정은 서버가 준 것만 선다 — 입고 전이면 거절만", async () => {
    const bundle = {
      ...ORDER.sellerOrders[0],
      status: "RETURN_REQUESTED",
      allowedActions: ["REJECT_RETURN"],
      rejectionReasons: ["PERIOD_EXPIRED", "RESTRICTED", "OTHER"],
      returnRequest: {
        status: "REQUESTED", reasonCode: "DEFECT", requestedAt: "2026-09-23T02:00:00Z",
        receivedAt: null, inspectedAt: null, decidedAt: null, allowedActions: [],
      },
    };
    const { container } = render(<OrderDetailView order={{ ...ORDER, sellerOrders: [bundle] }} />);

    // 판정이 입고를 요구해서(`V63`) 서버가 승인을 안 실었다 — 화면이 상태를 보고 다시 고르지 않는다.
    expect(screen.getByRole("button", { name: "반품 거절" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "반품 승인" })).not.toBeInTheDocument();
    expect(screen.getByText("표시·광고와 다름")).toBeInTheDocument();
    // 고를 수 있는 사유는 서버가 싣는다(`Q235`) — 목록에 없으면 옵션이 없다. 검수 전이라 훼손이 안 왔다.
    expect(screen.queryByRole("option", { name: "상품 훼손" })).not.toBeInTheDocument();
    expect(screen.getByRole("option", { name: "청약철회 기간 경과" })).toBeInTheDocument();
    expect(screen.getByText("상품 훼손은 검수 소견이 있어야 고를 수 있습니다.")).toBeInTheDocument();
    await expectNoAxeViolations(container);
  });

  it("입고 뒤에는 승인 폼이 재고 복구를 묻는다", async () => {
    const bundle = {
      ...ORDER.sellerOrders[0],
      status: "RETURN_REQUESTED",
      allowedActions: ["APPROVE_RETURN", "REJECT_RETURN"],
      rejectionReasons: ["DAMAGED", "PERIOD_EXPIRED", "RESTRICTED", "OTHER"],
      returnRequest: {
        status: "INSPECTED", reasonCode: "CHANGE_OF_MIND", requestedAt: "2026-09-23T02:00:00Z",
        receivedAt: "2026-09-24T02:00:00Z", inspectedAt: "2026-09-24T02:00:00Z", decidedAt: null, allowedActions: [],
      },
    };
    const { container } = render(<OrderDetailView order={{ ...ORDER, sellerOrders: [bundle] }} />);

    expect(screen.getByRole("button", { name: "반품 승인" })).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: "다시 판매합니다" })).toBeChecked();
    expect(screen.getByText("검수 마침")).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "상품 훼손" })).toBeInTheDocument();
    await expectNoAxeViolations(container);
  });
});
