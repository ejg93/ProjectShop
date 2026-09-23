import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
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

import WebhookDeliveriesPage from "./[id]/page";
import { WebhookForm } from "./webhook-actions";

afterEach(() => {
  vi.clearAllMocks();
});

/**
 * 셀러 웹훅 화면(`Q175`).
 *
 * <p><b>시크릿은 등록 응답에만 있다</b>(`29`) — 서버가 암호문만 들고 있어서 다시 못 준다. 화면이 그것을 받은 자리에서
 * 보여 주고, 다시 그리면 없어야 한다.
 */
describe("셀러 웹훅", () => {
  it("시크릿은 등록한 자리에서 한 번만 보인다", async () => {
    api.mockResolvedValue({ webhookEndpointId: 3, secret: "whsec_c2VjcmV0" });
    const { container } = render(<WebhookForm sellerId={1} />);

    expect(screen.queryByText("whsec_c2VjcmV0")).not.toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("받을 주소 (https)"), { target: { value: "https://example.com/hook" } });
    fireEvent.click(screen.getByLabelText("반품 상태가 바뀜"));
    fireEvent.submit(screen.getByRole("button", { name: "등록" }).closest("form")!);

    expect(await screen.findByText("whsec_c2VjcmV0")).toBeInTheDocument();
    expect(api).toHaveBeenCalledWith("/api/seller/webhooks", {
      method: "POST",
      body: { sellerId: 1, url: "https://example.com/hook", eventTypes: ["RETURN_REQUEST_STATUS_CHANGED"] },
    });
    await expectNoAxeViolations(container);

    // 화면을 다시 열면 없다 — 상태에만 있었고 어디에도 안 남겼다.
    cleanup();
    render(<WebhookForm sellerId={1} />);
    expect(screen.queryByText("whsec_c2VjcmV0")).not.toBeInTheDocument();
  });

  it("사건을 안 고르면 보내지 않는다", async () => {
    render(<WebhookForm sellerId={1} />);

    fireEvent.change(screen.getByLabelText("받을 주소 (https)"), { target: { value: "https://example.com/hook" } });
    fireEvent.submit(screen.getByRole("button", { name: "등록" }).closest("form")!);

    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("받을 사건을 하나 이상 골라 주세요."));
    expect(api).not.toHaveBeenCalled();
  });

  it("다시 보내기는 서버가 RESEND 를 준 줄에만 선다", async () => {
    apiSession
      .mockResolvedValueOnce({
        webhookEndpointId: 3, sellerId: 1, url: "https://example.com/hook",
        eventTypes: ["RETURN_REQUEST_STATUS_CHANGED"], createdAt: "2026-09-24T00:00:00Z",
      })
      .mockResolvedValueOnce({
        items: [
          delivery(11, "EXHAUSTED", ["RESEND"]),
          delivery(12, "SENT", []),
          // 상태만 보고 고르면 이 줄에도 버튼이 선다 — 서버가 안 줬으니 없어야 한다(`Q79`).
          delivery(13, "FAILED", []),
        ],
        page: 0, size: 20, total: 3,
      });

    const { container } = render(
      await WebhookDeliveriesPage({ params: Promise.resolve({ id: "3" }), searchParams: Promise.resolve({}) }),
    );

    expect(screen.getAllByRole("button", { name: "다시 보내기" })).toHaveLength(1);
    expect(apiSession).toHaveBeenLastCalledWith("/api/seller/webhooks/3/deliveries?page=0&size=20");
    await expectNoAxeViolations(container);
  });
});

function delivery(webhookDeliveryId: number, status: string, allowedActions: string[]) {
  return {
    webhookDeliveryId, eventType: "RETURN_REQUEST_STATUS_CHANGED", status, attemptCount: 8,
    nextAttemptAt: null, lastStatusCode: 503, lastError: null, createdAt: "2026-09-24T00:00:00Z",
    deliveredAt: status === "SENT" ? "2026-09-24T00:01:00Z" : null, allowedActions,
  };
}
