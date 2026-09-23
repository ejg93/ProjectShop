import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { expectNoAxeViolations } from "@/test/axe";

import { type OrderRefund, RefundLines } from "./refund-lines";

const REFUND: OrderRefund = {
  refundNumber: "R-20260923-AB12C",
  sellerOrderNumber: "S-1",
  status: "REQUESTED",
  reasonCode: "CANCELLED",
  amount: 20000,
  dueAt: "2026-09-28T15:00:00Z",
  overdue: false,
  createdAt: "2026-09-23T03:00:00Z",
};

/**
 * 구매자의 환불 줄(`Q187`, `D2` R5).
 *
 * <p><b>상태마다 답하는 물음이 다르다</b> — 대기면 「언제 오나」, 기한을 넘겼으면 「늦으면 어떻게 되나」, 반려면
 * 「왜 안 됐나」. 기한 판단은 서버 값({@code overdue})을 그대로 쓴다.
 */
describe("구매자의 환불 줄", () => {
  it("대기 중이면 돌려줄 날을 적는다", () => {
    render(<RefundLines refunds={[REFUND]} rejectionReasons={{}} />);

    expect(screen.getByText("주문 취소 · 승인 대기")).toBeInTheDocument();
    expect(screen.getByText(/2026\. 9\. 29\.까지 돌려드립니다/)).toBeInTheDocument();
  });

  it("기한을 넘겼으면 지연배상금을 더한다고 적는다", () => {
    render(<RefundLines refunds={[{ ...REFUND, overdue: true }]} rejectionReasons={{}} />);

    expect(screen.getByText(/지연배상금을 더해 돌려드립니다/)).toBeInTheDocument();
  });

  it("반려면 사유를 적는다", () => {
    render(
      <RefundLines
        refunds={[{ ...REFUND, status: "REJECTED" }]}
        rejectionReasons={{ [REFUND.refundNumber]: "반품 상품이 도착하지 않았습니다" }}
      />,
    );

    expect(screen.getByText("반려 사유: 반품 상품이 도착하지 않았습니다")).toBeInTheDocument();
  });

  it("요청 버튼은 없다 — 스위퍼가 요청한다", () => {
    render(<RefundLines refunds={[REFUND]} rejectionReasons={{}} />);

    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("기한 넘김과 반려가 같이 있어도 접근성 규칙을 지킨다(`Q193`)", async () => {
    const { container } = render(
      <RefundLines
        refunds={[{ ...REFUND, overdue: true }, { ...REFUND, refundNumber: "R-2", status: "REJECTED" }]}
        rejectionReasons={{ "R-2": "반품 상품이 도착하지 않았습니다" }}
      />,
    );

    await expectNoAxeViolations(container);
  });
});
