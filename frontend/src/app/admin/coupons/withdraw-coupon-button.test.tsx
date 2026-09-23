import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { api } from "@/lib/api";

import { WithdrawCouponButton } from "./withdraw-coupon-button";

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
 * 쿠폰 내리기(`Q186`).
 *
 * <p><b>받은 사람도 못 쓰게 된다는 것을 누르기 전에 알리는 것이 판단이다</b> — 서버가 그렇게 동작하는데 화면이 안
 * 알리면 「발급만 닫힌다」로 읽고 누른다.
 */
describe("쿠폰 내리기", () => {
  it("받은 사람도 못 쓴다고 묻고, 확인하면 그 쿠폰을 내린다", async () => {
    render(<WithdrawCouponButton couponId={12} code="WELCOME10" />);

    fireEvent.click(screen.getByRole("button", { name: /내리기/ }));
    expect(screen.getByText(/이미 받은 분도 더는 못 씁니다/)).toBeInTheDocument();
    expect(api).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "내리기" }));
    await waitFor(() => expect(api).toHaveBeenCalledWith("/api/coupons/12", { method: "DELETE" }));
  });
});
