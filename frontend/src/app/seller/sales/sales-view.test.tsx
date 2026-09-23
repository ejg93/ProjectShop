import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { expectNoAxeViolations } from "@/test/axe";

import { type SalesDay, type SalesReport, SalesView, salesRange } from "./sales-view";

const DAY: SalesDay = {
  salesDate: "2026-09-22",
  orderCount: 2,
  soldQuantity: 3,
  paidAmount: 30000,
  refundCount: 1,
  refundedAmount: 10000,
  netAmount: 20000,
  mallDiscountAmount: 1000,
  refundedMallDiscountAmount: 0,
  sellerNetAmount: 21000,
};

const REPORT: SalesReport = {
  from: "2026-09-22",
  to: "2026-09-24",
  days: [DAY, { ...DAY, salesDate: "2026-09-23", orderCount: 1, soldQuantity: 1, paidAmount: 5000, refundCount: 0,
    refundedAmount: 0, netAmount: 5000, mallDiscountAmount: 0, sellerNetAmount: 5000 }],
  total: { orderCount: 3, soldQuantity: 4, paidAmount: 35000, refundCount: 1, refundedAmount: 10000, netAmount: 25000,
    mallDiscountAmount: 1000, refundedMallDiscountAmount: 0, sellerNetAmount: 26000 },
};

/**
 * 매출 통계 화면(`41a`).
 *
 * <p><b>누구의 합인지는 여기서 안 잰다</b> — 서버가 스코프로 가르고 {@code SalesStatsApiTest} 가 그것을 잰다.
 * 여기는 기간 계산과 표의 순서·합계를 본다.
 */
describe("매출 통계", () => {
  it("기간의 끝은 내일이다 — 서버가 끝을 안 넣는다", () => {
    expect(salesRange(7, "2026-09-23")).toEqual({ from: "2026-09-17", to: "2026-09-24" });
    expect(salesRange(30, "2026-03-01")).toEqual({ from: "2026-01-31", to: "2026-03-02" });
  });

  it("최근 날부터 그리고 합계 줄을 단다", () => {
    render(<SalesView report={REPORT} range={7} />);

    const rows = screen.getAllByRole("row");
    expect(within(rows[1]).getByText("2026. 9. 23.")).toBeInTheDocument();
    expect(within(rows[2]).getByText("2026. 9. 22.")).toBeInTheDocument();

    const totalRow = rows[rows.length - 1];
    expect(within(totalRow).getByRole("rowheader", { name: "합계" })).toBeInTheDocument();
    expect(within(totalRow).getByText("25,000원")).toBeInTheDocument();
  });

  /** 몰이 문 쿠폰이면 셀러는 정가를 받는다(`Q197`) — 고객 결제만 세면 정산서보다 할인액만큼 작다 */
  it("셀러 매출을 순매출 옆에 적는다 — 정산서와 맞춰 볼 축이다", () => {
    render(<SalesView report={REPORT} range={7} />);

    const rows = screen.getAllByRole("row");
    expect(within(rows[rows.length - 1]).getByText("26,000원")).toBeInTheDocument();
    expect(screen.getByText("셀러 매출 (정산 금액 축)")).toBeInTheDocument();
  });

  it("고른 기간을 알린다", () => {
    render(<SalesView report={REPORT} range={30} />);

    expect(screen.getByRole("link", { name: "최근 30일" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "최근 7일" })).not.toHaveAttribute("aria-current");
  });

  it("표와 기간 고르기가 접근성 규칙을 지킨다(`Q193`)", async () => {
    const { container } = render(<SalesView report={REPORT} range={30} />);

    await expectNoAxeViolations(container);
  });
});
