import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { api } from "@/lib/api";

import { ReportActions, RestoreForm } from "./report-actions";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: () => {} }) }));

// 서버를 진짜로 부르지 않는다. `ApiError` 는 진짜를 그대로 둔다 — 화면이 그 타입으로 분기한다.
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
 * 신고 처리 화면이 무엇을 보내나(`Q171`).
 *
 * <p><b>되살리기는 이유를 받는 것이 판단이다.</b> 그 이유가 감사에 남는 유일한 자리라(`Q167`)
 * 누가 「이유 칸은 거추장스럽다」고 빼면 「누가 왜 되살렸나」에 다시 못 답하게 된다.
 */
describe("후기 신고 처리", () => {
  it("받아들이기는 accept 로 간다 — 사유를 따로 안 고른다", async () => {
    render(<ReportActions reportId={31} />);

    fireEvent.click(screen.getByRole("button", { name: "받아들이고 게시 중단" }));

    await waitFor(() =>
      expect(api).toHaveBeenCalledWith("/api/review-reports/31/accept", { method: "POST" }),
    );
  });

  it("되살리기는 이유 칸이 있어야 보낸다", async () => {
    render(<RestoreForm reviewId={9} />);
    fireEvent.click(screen.getByRole("button", { name: "후기 되살리기" }));

    const note = screen.getByLabelText(/되살리는 이유/);
    expect(note).toBeRequired();

    fireEvent.change(note, { target: { value: "이의제기 문의 확인" } });
    fireEvent.submit(note.closest("form")!);

    await waitFor(() =>
      expect(api).toHaveBeenCalledWith("/api/reviews/9/restore", {
        method: "POST",
        body: { note: "이의제기 문의 확인" },
      }),
    );
  });
});
