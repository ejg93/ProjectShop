import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { expectNoAxeViolations } from "@/test/axe";

import { OrderFilters, OrderTable, apiQueryOf, filterOf, isReversed } from "./order-list";

/**
 * 관리자 주문 조회(`Q176`).
 *
 * <p><b>끝날이 사람과 서버에서 다르다</b> — 사람은 그날을 넣고 서버의 {@code to} 는 뺀다. 하루를 안 밀면
 * 「오늘까지」를 고른 목록에서 오늘 주문이 빠진다. 그리고 모르는 값은 서버에 안 보낸다 — 400 이 오면
 * 조건을 고칠 자리 대신 오류가 그려진다.
 */
describe("관리자 주문 조회", () => {
  it("끝날을 하루 밀어 서버의 to 로 보낸다 — 달이 넘어가도", () => {
    expect(apiQueryOf({ status: "PAID", from: "2026-09-01", until: "2026-09-30" })).toEqual({
      status: "PAID",
      from: "2026-09-01",
      to: "2026-10-01",
    });
  });

  it("모르는 상태와 날짜 꼴이 아닌 값은 버린다", () => {
    expect(filterOf({ status: "SHIPPING", from: "어제", until: "2026-09-23" })).toEqual({
      status: "",
      from: "",
      until: "2026-09-23",
    });
  });

  it("거꾸로 된 기간을 알아본다 — 같은 날은 하루짜리 기간이다", () => {
    expect(isReversed({ status: "", from: "2026-09-24", until: "2026-09-23" })).toBe(true);
    expect(isReversed({ status: "", from: "2026-09-23", until: "2026-09-23" })).toBe(false);
  });

  it("주문번호가 상세로 가는 링크다", () => {
    render(
      <OrderTable
        items={[
          {
            orderNumber: "20260923-ABC123",
            status: "PAID",
            payableAmount: 12000,
            itemCount: 2,
            createdAt: "2026-09-23T01:00:00Z",
          },
        ]}
      />,
    );

    expect(screen.getByRole("link", { name: "20260923-ABC123" })).toHaveAttribute(
      "href",
      "/admin/orders/20260923-ABC123",
    );
    expect(screen.getByText("결제 완료")).toBeInTheDocument();
  });

  it("거르는 칸과 표에 접근성 위반이 없다", async () => {
    const { container } = render(
      <>
        <OrderFilters filter={{ status: "PAID", from: "2026-09-01", until: "" }} />
        <OrderTable
          items={[
            {
              orderNumber: "20260923-ABC123",
              status: "PAYMENT_PENDING",
              payableAmount: 3000,
              itemCount: 1,
              createdAt: "2026-09-23T01:00:00Z",
            },
          ]}
        />
      </>,
    );

    await expectNoAxeViolations(container);
  });
});
