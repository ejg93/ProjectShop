import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { api } from "@/lib/api";

import { BlockInquiryButton } from "./block-inquiry-button";

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
 * 문의 게시 중단(`Q184`, `D2` `R34`).
 *
 * <p><b>사유를 고르게 하는 것이 판단이다</b> — 광고(법, 제50조의7)와 욕설(약관)이 값으로 갈려야 개정될 때 무엇을
 * 고칠지 안다. 한 번에 내리는 버튼 하나로 두면 사유가 하나로 뭉친다.
 */
describe("문의 게시 중단", () => {
  it("사유를 묻고 고른 사유로 보낸다", async () => {
    render(<BlockInquiryButton inquiryNumber="Q-20260923-AB12C" />);

    fireEvent.click(screen.getByRole("button", { name: "게시 중단" }));
    expect(api).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "광고성 정보" }));

    await waitFor(() =>
      expect(api).toHaveBeenCalledWith("/api/inquiries/Q-20260923-AB12C/block", {
        method: "POST",
        body: { reason: "ADVERTISEMENT" },
      }),
    );
  });
});
