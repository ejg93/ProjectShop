import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { api } from "@/lib/api";

import { CopyrightDecisionButtons } from "../admin/copyright-reports/decision-buttons";
import { CopyrightReportForm } from "./report-form";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: () => {} }) }));

vi.mock("@/lib/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api")>()),
  api: vi.fn(),
}));

beforeEach(() => {
  vi.mocked(api).mockResolvedValue({ copyrightReportId: 42 });
});

afterEach(() => {
  vi.restoreAllMocks();
});

/**
 * 저작권 신고 화면(`Q183`, `D2` `R42`).
 *
 * <p><b>고른 사진으로 보낸다</b> — 서버가 사진 단위로 받고 판정하면 그 사진이 지워진다. 첫 사진으로 굳어 버리면
 * 권리자가 다른 사진을 신고해도 엉뚱한 사진이 내려간다.
 */
describe("저작권 신고", () => {
  it("고른 사진 번호로 신고를 보내고 접수 번호를 알린다", async () => {
    const { container } = render(
      <CopyrightReportForm
        images={[
          { imageId: 7, url: "https://example.test/a.jpg" },
          { imageId: 9, url: "https://example.test/b.jpg" },
        ]}
      />,
    );

    fireEvent.click(screen.getByAltText("상품 사진 2"));
    fireEvent.change(screen.getByLabelText("신고하시는 분"), { target: { value: "권리자" } });
    fireEvent.change(screen.getByLabelText("연락받을 이메일"), { target: { value: "r@test.local" } });
    fireEvent.change(screen.getByLabelText("권리가 있는 저작물"), { target: { value: "우리 화보" } });
    fireEvent.submit(container.querySelector("form")!);

    await waitFor(() =>
      expect(api).toHaveBeenCalledWith("/api/copyright-reports/images/9", {
        method: "POST",
        body: { reporterName: "권리자", reporterEmail: "r@test.local", claimedWork: "우리 화보" },
      }),
    );
    expect(await screen.findByRole("status")).toHaveTextContent("접수 번호는 42");
  });

  it("게시 중단은 한 번 더 묻고 소문자 판정값으로 보낸다", async () => {
    render(<CopyrightDecisionButtons reportId={5} />);

    fireEvent.click(screen.getByRole("button", { name: "게시 중단(사진 삭제)" }));
    expect(api).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "게시 중단" }));
    await waitFor(() =>
      expect(api).toHaveBeenCalledWith("/api/copyright-reports/5/decision", {
        method: "POST",
        body: { decision: "taken_down" },
      }),
    );
  });
});
