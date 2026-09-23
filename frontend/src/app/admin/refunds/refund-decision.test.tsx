import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError, api } from "@/lib/api";

import { RefundDecision } from "./refund-decision";

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

/**
 * 환불 승인·반려(`Q185`).
 *
 * <p><b>누가 무엇을 받는지는 여기서 안 잰다</b> — 서버가 대기 상태·권한·자기 요청으로 고르고
 * {@code RefundApiTest} 가 그것을 잰다. 여기는 이름을 버튼과 입구로 바꾸는 자리다.
 */
describe("환불 승인·반려", () => {
  it("허용 동작이 없으면 아무것도 안 그린다", () => {
    const { container } = render(<RefundDecision refundNumber="R-1" amount={20000} overdue={false} allowedActions={[]} />);

    expect(container).toBeEmptyDOMElement();
  });

  it("승인은 금액을 적어 한 번 묻고, 확인하면 그 입구로 간다", async () => {
    render(<RefundDecision refundNumber="R-1" amount={20000} overdue={false} allowedActions={["APPROVE", "REJECT"]} />);

    fireEvent.click(screen.getByRole("button", { name: "승인" }));
    expect(screen.getByText(/20,000원을 돌려줍니다/)).toBeInTheDocument();
    expect(api).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "승인" }));
    await waitFor(() =>
      expect(api).toHaveBeenCalledWith("/api/refunds/R-1/approve", { method: "POST", body: undefined }),
    );
  });

  it("기한을 넘긴 건은 지연배상금이 더해진다고 묻는다", () => {
    render(<RefundDecision refundNumber="R-1" amount={20000} overdue allowedActions={["APPROVE", "REJECT"]} />);

    fireEvent.click(screen.getByRole("button", { name: "승인" }));

    expect(screen.getByText(/20,000원에 지연배상금을 더해 돌려줍니다/)).toBeInTheDocument();
  });

  it("반려는 사유를 받아 보낸다", async () => {
    render(<RefundDecision refundNumber="R-1" amount={20000} overdue={false} allowedActions={["APPROVE", "REJECT"]} />);

    fireEvent.click(screen.getByRole("button", { name: "반려" }));
    expect(screen.getByRole("button", { name: "반려" })).toBeDisabled();

    fireEvent.change(screen.getByLabelText("반려 사유"), { target: { value: "반품 상품이 아직 안 들어왔습니다" } });
    fireEvent.click(screen.getByRole("button", { name: "반려" }));

    await waitFor(() =>
      expect(api).toHaveBeenCalledWith("/api/refunds/R-1/reject", {
        method: "POST",
        body: { reason: "반품 상품이 아직 안 들어왔습니다" },
      }),
    );
  });

  it("이미 처리된 것은 그렇다고 알린다", async () => {
    vi.mocked(api).mockRejectedValue(
      new ApiError(409, "tag:projectshop.example,2026:error:refund-already-decided", "이미 처리됐다"),
    );
    render(<RefundDecision refundNumber="R-1" amount={20000} overdue={false} allowedActions={["APPROVE", "REJECT"]} />);

    fireEvent.click(screen.getByRole("button", { name: "승인" }));
    fireEvent.click(screen.getByRole("button", { name: "승인" }));

    expect(await screen.findByText(/이미 처리된 환불입니다/)).toBeInTheDocument();
  });
});
