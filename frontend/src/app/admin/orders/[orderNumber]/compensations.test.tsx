import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { expectNoAxeViolations } from "@/test/axe";

import { api } from "@/lib/api";

import { Compensations } from "./compensations";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: () => {} }) }));

vi.mock("@/lib/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api")>()),
  api: vi.fn(),
}));

beforeEach(() => {
  vi.mocked(api).mockResolvedValue(undefined);
});

afterEach(() => {
  vi.restoreAllMocks();
});

const DECIDED = {
  kind: "NON_DELIVERY",
  bearer: "SELLER",
  amount: 5000,
  reason: "택배 분실, 재발송 불가",
  inquiryNumber: null,
  decidedAt: "2026-09-23T01:00:00Z",
};

/**
 * 손해배상 판정(`43a-4c`).
 *
 * <p><b>판정 칸은 서버가 준 동작으로만 선다</b> — 감사자가 판정 칸을 보면 누르고 404 를 받는다.
 * 그리고 <b>빈 문의 번호는 안 보낸다</b>: 빈 문자열을 보내면 서버의 번호 꼴 검사에 걸려 400 이 난다.
 */
describe("손해배상", () => {
  it("판정 권한이 없으면 목록만 보인다", () => {
    render(<Compensations sellerOrderNumber="S-1" listing={{ items: [DECIDED], allowedActions: [] }} />);

    expect(screen.getByText(/미인도 · 셀러 부담/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "배상 판정 남기기" })).not.toBeInTheDocument();
  });

  it("판정을 보내고, 빈 문의 번호는 null 로 보낸다", async () => {
    render(<Compensations sellerOrderNumber="S-1" listing={{ items: [], allowedActions: ["COMPENSATE"] }} />);

    fireEvent.change(screen.getByLabelText("금액(원)"), { target: { value: "5000" } });
    fireEvent.change(screen.getByLabelText(/왜 그 금액인가/), { target: { value: "택배 분실" } });
    fireEvent.submit(screen.getByRole("button", { name: "배상 판정 남기기" }).closest("form")!);

    await waitFor(() =>
      expect(api).toHaveBeenCalledWith("/api/shipments/S-1/compensate", {
        method: "POST",
        body: { kind: "NON_DELIVERY", bearer: "SELLER", amount: 5000, basis: "택배 분실", inquiryNumber: null },
      }),
    );
  });

  it("접근성 위반이 없다", async () => {
    const { container } = render(
      <Compensations sellerOrderNumber="S-1" listing={{ items: [DECIDED], allowedActions: ["COMPENSATE"] }} />,
    );

    await expectNoAxeViolations(container);
  });
});
