import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ApiError, api } from "@/lib/api";
import { expectNoAxeViolations } from "@/test/axe";

vi.mock("@/lib/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api")>()),
  api: vi.fn(),
}));
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: () => {} }) }));

import { ReinstatementButtons } from "./reinstatement-buttons";

afterEach(() => {
  vi.mocked(api).mockReset();
});

/**
 * 재개 절차 버튼이 단계마다 맞는 입구를 부르는지 본다(`Q467`, 저작권법 제103조③ · 시행령 제42·43조).
 */
describe("재개 절차 버튼", () => {
  it("요구가 없으면 소명과 함께 재개 요구를 기록한다", async () => {
    vi.mocked(api).mockResolvedValue(undefined);
    const { container } = render(<ReinstatementButtons reportId={9} reinstatement={null} />);

    fireEvent.change(screen.getByLabelText("소명"), { target: { value: "제가 찍은 사진입니다" } });
    fireEvent.click(screen.getByRole("button", { name: "재개 요구 접수" }));

    await waitFor(() => expect(api).toHaveBeenCalledTimes(1));
    const [path, init] = vi.mocked(api).mock.calls[0] as [string, { method: string; body: Record<string, string> }];
    expect(path).toBe("/api/copyright-reports/9/reinstatement");
    expect(init.method).toBe("POST");
    expect(init.body.requesterClaim).toBe("제가 찍은 사진입니다");
    expect(init.body.requestedAt).toMatch(/^\d{4}-\d{2}-\d{2}T/);
    expect(screen.getByLabelText("소명")).toHaveAttribute("maxLength", "2000");
    await expectNoAxeViolations(container);
  });

  it("요구가 판단 전이면 예정일은 요구받은 날의 7일 뒤부터 14일 뒤까지다", async () => {
    vi.mocked(api).mockResolvedValue(undefined);
    const { container } = render(
      <ReinstatementButtons
        reportId={9}
        reinstatement={{ requestedAt: "2026-11-14T01:00:00Z", decision: null, scheduledFor: null, suitNotifiedAt: null, reinstatedAt: null }}
      />,
    );

    const date = screen.getByLabelText("재개 예정일");
    expect(date).toHaveAttribute("min", "2026-11-21");
    expect(date).toHaveAttribute("max", "2026-11-28");
    fireEvent.change(date, { target: { value: "2026-11-21" } });
    fireEvent.click(screen.getByRole("button", { name: "재개 예정일 정하기" }));

    await waitFor(() => expect(api).toHaveBeenCalledWith("/api/copyright-reports/9/reinstatement/decision", {
      method: "POST",
      body: { decision: "SCHEDULED", scheduledFor: "2026-11-21" },
    }));
    fireEvent.click(screen.getByRole("button", { name: "거절" }));
    await waitFor(() => expect(api).toHaveBeenCalledWith("/api/copyright-reports/9/reinstatement/decision", {
      method: "POST",
      body: { decision: "REFUSED" },
    }));
    await expectNoAxeViolations(container);
  });

  it("예정이면 소 제기 통보를 기록한다", async () => {
    vi.mocked(api).mockResolvedValue(undefined);
    render(
      <ReinstatementButtons
        reportId={9}
        reinstatement={{ requestedAt: "2026-11-14T01:00:00Z", decision: "SCHEDULED", scheduledFor: "2026-11-21", suitNotifiedAt: null, reinstatedAt: null }}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "소 제기 통보 받음" }));

    await waitFor(() => expect(vi.mocked(api).mock.calls[0][0]).toBe("/api/copyright-reports/9/reinstatement/suit-notice"));
  });

  it("30일이 지났으면 그렇게 알린다", async () => {
    vi.mocked(api).mockRejectedValue(
      new ApiError(409, "tag:projectshop.example,2026:error:copyright-reinstatement-late", "late"),
    );
    render(<ReinstatementButtons reportId={9} reinstatement={null} />);

    fireEvent.change(screen.getByLabelText("소명"), { target: { value: "소명" } });
    fireEvent.click(screen.getByRole("button", { name: "재개 요구 접수" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("통보 뒤 30일이 지나 재개 요구를 받을 수 없습니다.");
  });
});
