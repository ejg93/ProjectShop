import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { api } from "@/lib/api";

import { ProductActions, productActionsFor } from "./product-actions";

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
 * 상품 상태 버튼(`Q182`).
 *
 * <p><b>누가 무엇을 받는지는 여기서 안 잰다</b> — 서버가 전이표와 판정으로 이름을 고르고
 * {@code ProductReviewActionsTest} 가 그것을 잰다. 여기는 이름을 버튼과 입구로 바꾸는 자리다.
 */
describe("상품 상태 버튼", () => {
  it("모르는 이름은 버튼이 안 난다", () => {
    expect(productActionsFor(["SUBMIT_REVIEW", "SELF_DESTRUCT"]).map((action) => action.label))
      .toEqual(["검수 요청"]);
  });

  it("검수 요청은 바로 그 입구로 간다", async () => {
    render(<ProductActions productId={3} allowedActions={["SUBMIT_REVIEW"]} />);

    fireEvent.click(screen.getByRole("button", { name: "검수 요청" }));

    await waitFor(() =>
      expect(api).toHaveBeenCalledWith("/api/products/3/submit-review", { method: "POST", body: undefined }),
    );
  });

  it("반려는 사유를 받아 note 로 보낸다", async () => {
    render(<ProductActions productId={3} allowedActions={["APPROVE", "REJECT"]} />);

    fireEvent.click(screen.getByRole("button", { name: "반려" }));
    fireEvent.change(screen.getByLabelText("반려 사유"), { target: { value: "상품 설명에 성분 표시가 없다" } });
    fireEvent.click(screen.getByRole("button", { name: "반려" }));

    await waitFor(() =>
      expect(api).toHaveBeenCalledWith("/api/products/3/reject", {
        method: "POST",
        body: { note: "상품 설명에 성분 표시가 없다" },
      }),
    );
  });
});
