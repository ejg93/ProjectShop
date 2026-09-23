import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError, api } from "@/lib/api";

import { ShipForm } from "./ship-form";

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
 * 송장과 함께 발송(`57`).
 *
 * <p><b>송장이 빠진 발송을 화면이 못 보낸다</b> — 버튼이 둘 다 찰 때까지 잠겨 있다. 서버가 400 으로 한 번 더 막는다
 * ({@code ShipmentTrackingTest}).
 */
describe("송장과 함께 발송", () => {
  it("택배사와 송장이 다 차야 보낼 수 있고, 적은 그대로 보낸다", async () => {
    render(<ShipForm sellerOrderNumber="S-20260923-AB12C" />);
    const submit = screen.getByRole("button", { name: "발송 처리" });

    expect(submit).toBeDisabled();
    fireEvent.change(screen.getByLabelText("택배사"), { target: { value: "HANJIN" } });
    expect(submit).toBeDisabled();
    fireEvent.change(screen.getByLabelText("송장 번호"), { target: { value: "1234-5678-9012" } });
    fireEvent.click(submit);

    await waitFor(() =>
      expect(api).toHaveBeenCalledWith("/api/shipments/S-20260923-AB12C/ship", {
        method: "POST",
        body: { carrierCode: "HANJIN", trackingNo: "1234-5678-9012" },
      }),
    );
  });

  it("형식이 틀리면 무엇을 고칠지 알린다", async () => {
    vi.mocked(api).mockRejectedValue(
      new ApiError(400, "tag:projectshop.example,2026:error:validation-failed", "형식이 맞지 않는다"),
    );
    render(<ShipForm sellerOrderNumber="S-1" />);

    fireEvent.change(screen.getByLabelText("택배사"), { target: { value: "CJ" } });
    fireEvent.change(screen.getByLabelText("송장 번호"), { target: { value: "12345" } });
    fireEvent.click(screen.getByRole("button", { name: "발송 처리" }));

    expect(await screen.findByText("택배사와 송장 번호를 확인해 주세요.")).toBeInTheDocument();
  });
});
