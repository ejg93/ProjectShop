import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { expectNoAxeViolations } from "@/test/axe";

const { api, apiSession } = vi.hoisted(() => ({ api: vi.fn(), apiSession: vi.fn() }));

vi.mock("@/lib/api", async (original) => ({ ...(await original<typeof import("@/lib/api")>()), api }));
vi.mock("@/lib/api-session", () => ({ apiSession }));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ refresh: () => {} }),
  notFound: () => {
    throw new Error("notFound");
  },
}));

import SellerOrderDetailPage from "./page";
import { ReceiveForm } from "./receive-form";

afterEach(() => {
  vi.clearAllMocks();
});

const DETAIL = {
  sellerOrderNumber: "S-1",
  orderNumber: "O-1",
  status: "RETURN_REQUESTED",
  shippingFee: 3000,
  shipDueAt: null,
  shippedAt: null,
  shipOverdue: false,
  deliveredAt: "2026-09-20T00:00:00Z",
  withdrawalExpireAt: "2026-09-27T00:00:00Z",
  autoConfirmAt: null,
  createdAt: "2026-09-19T00:00:00Z",
  returnReason: "DEFECT",
  returnRequest: {
    status: "REQUESTED", reasonCode: "DEFECT", requestedAt: "2026-09-23T00:00:00Z",
    receivedAt: null, inspectedAt: null, decidedAt: null, allowedActions: ["RECEIVE"],
  },
  items: [],
  allowedActions: [],
};

/**
 * 반품 입고(`43a-5`).
 *
 * <p><b>소견은 고르는 칸이다</b> — 적으면 한 번에 검수까지, 비우면 입고만(2026-09-23 결정).
 */
describe("반품 입고", () => {
  it("소견을 적으면 다듬어 싣고, 비우면 빈 본문을 보낸다", async () => {
    api.mockResolvedValue(undefined);
    render(<ReceiveForm sellerOrderNumber="S-1" />);

    fireEvent.change(screen.getByLabelText("검수 소견 (선택)"), { target: { value: "  본품 이상 없음 " } });
    fireEvent.submit(screen.getByRole("button", { name: "입고 확인" }).closest("form")!);
    await waitFor(() => expect(api).toHaveBeenCalledTimes(1));
    expect(api).toHaveBeenCalledWith("/api/returns/S-1/receive", {
      method: "POST",
      body: { inspectionNote: "본품 이상 없음" },
    });

    fireEvent.change(screen.getByLabelText("검수 소견 (선택)"), { target: { value: "   " } });
    fireEvent.submit(screen.getByRole("button", { name: "입고 확인" }).closest("form")!);
    await waitFor(() => expect(api).toHaveBeenCalledTimes(2));
    expect(api).toHaveBeenLastCalledWith("/api/returns/S-1/receive", { method: "POST", body: {} });
  });

  it("상세는 서버가 RECEIVE 를 줄 때만 입고 폼을 그리고, 하자 반품을 판매자 부담으로 알린다", async () => {
    apiSession.mockResolvedValue(DETAIL);

    const { container, unmount } = render(
      await SellerOrderDetailPage({ params: Promise.resolve({ sellerOrderNumber: "S-1" }) }),
    );

    expect(screen.getByRole("button", { name: "입고 확인" })).toBeInTheDocument();
    // 응답은 대문자다. 소문자와 비교하던 동안 하자 반품에도 「구매자 부담」이 떴다.
    expect(screen.getByText(/반환에 드는 비용은 판매자가 부담하며/)).toBeInTheDocument();
    await expectNoAxeViolations(container);
    unmount();

    apiSession.mockResolvedValue({ ...DETAIL, returnRequest: { ...DETAIL.returnRequest, allowedActions: [] } });
    render(await SellerOrderDetailPage({ params: Promise.resolve({ sellerOrderNumber: "S-1" }) }));
    expect(screen.queryByRole("button", { name: "입고 확인" })).not.toBeInTheDocument();
  });
});
