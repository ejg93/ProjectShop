import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ApiError, api } from "@/lib/api";
import { expectNoAxeViolations } from "@/test/axe";

vi.mock("@/lib/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api")>()),
  api: vi.fn(),
}));

import { ResetSecondFactorButton } from "./reset-second-factor-button";

afterEach(() => {
  vi.mocked(api).mockReset();
  vi.restoreAllMocks();
});

/**
 * 「2차 인증 초기화」가 확인 뒤에만 그 사람의 주소로 DELETE 를 보내고 결과를 알리는지 본다(`Q462`).
 */
describe("2차 인증 초기화 버튼", () => {
  it("확인하면 그 사람의 주소로 DELETE 를 보내고 다음 로그인을 알린다", async () => {
    vi.spyOn(window, "confirm").mockReturnValue(true);
    vi.mocked(api).mockResolvedValue(undefined);
    const { container } = render(<ResetSecondFactorButton userId={5} />);

    fireEvent.click(screen.getByRole("button", { name: "2차 인증 초기화" }));

    await waitFor(() => expect(api).toHaveBeenCalledWith("/api/users/5/second-factor", { method: "DELETE" }));
    expect(await screen.findByRole("status"))
      .toHaveTextContent("2차 인증을 초기화했습니다. 다음 로그인에서 다시 등록합니다.");
    await expectNoAxeViolations(container);
  });

  it("확인을 취소하면 부르지 않는다", () => {
    vi.spyOn(window, "confirm").mockReturnValue(false);
    render(<ResetSecondFactorButton userId={5} />);

    fireEvent.click(screen.getByRole("button", { name: "2차 인증 초기화" }));

    expect(api).not.toHaveBeenCalled();
  });

  it("권한이 없으면 그렇게 알린다", async () => {
    vi.spyOn(window, "confirm").mockReturnValue(true);
    vi.mocked(api).mockRejectedValue(new ApiError(403, "tag:projectshop.example,2026:error:access-denied", "denied"));
    render(<ResetSecondFactorButton userId={5} />);

    fireEvent.click(screen.getByRole("button", { name: "2차 인증 초기화" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("2차 인증을 초기화할 권한이 없습니다.");
  });
});
