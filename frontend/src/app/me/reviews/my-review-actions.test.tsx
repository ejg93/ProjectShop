import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { api } from "@/lib/api";

import { MyReviewActions } from "./my-review-actions";

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
 * 내 후기의 고치기·지우기(`Q171`).
 *
 * <p><b>지우기를 한 번 더 묻는 것이 판단이다</b> — 목록을 훑다 잘못 누른 손이 그대로 남지 않게.
 * 그리고 <b>별점과 글을 같이 보낸다</b>(`Q160`) — 글만 보내면 별과 말이 갈린다.
 */
describe("내 후기 조작", () => {
  it("지우기는 한 번 더 묻고, 그다음에야 보낸다", async () => {
    render(<MyReviewActions reviewId={5} rating={4} body="열 자가 넘는 원래 후기입니다" />);

    fireEvent.click(screen.getByRole("button", { name: "지우기" }));
    expect(api).not.toHaveBeenCalled();
    expect(screen.getByText("이 후기를 지우시겠습니까?")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "지우기" }));
    await waitFor(() =>
      expect(api).toHaveBeenCalledWith("/api/reviews/5", { method: "DELETE" }),
    );
  });

  /** 내려간 후기는 지워도 자리를 차지한다(`Q194`, `V105`). 모르고 지우면 새로 쓸 수 있다고 믿는다 */
  it("게시가 중단된 후기는 지워도 새로 못 쓴다고 묻는다", () => {
    render(<MyReviewActions reviewId={5} rating={4} body="열 자가 넘는 원래 후기입니다" blocked />);

    fireEvent.click(screen.getByRole("button", { name: "지우기" }));

    expect(screen.getByText(/지워도 이 주문에 새 후기를 쓸 수 없습니다/)).toBeInTheDocument();
  });

  it("고치면 별점과 글을 같이 보낸다", async () => {
    render(<MyReviewActions reviewId={5} rating={4} body="열 자가 넘는 원래 후기입니다" />);

    fireEvent.click(screen.getByRole("button", { name: "고치기" }));
    fireEvent.change(screen.getByLabelText("별점"), { target: { value: "2" } });
    fireEvent.submit(screen.getByLabelText(/고칠 내용/).closest("form")!);

    await waitFor(() =>
      expect(api).toHaveBeenCalledWith("/api/reviews/5", {
        method: "PATCH",
        body: { rating: 2, body: "열 자가 넘는 원래 후기입니다" },
      }),
    );
  });
});
