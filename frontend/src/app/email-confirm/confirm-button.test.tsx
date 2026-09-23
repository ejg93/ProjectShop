import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError, api } from "@/lib/api";

import { EmailConfirmButton } from "./confirm-button";

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
 * 이메일 변경 확정(`Q181`).
 *
 * <p><b>열자마자 보내지 않는 것이 판단이다</b> — 메일 검사기가 링크를 미리 열면 사람이 누르기 전에
 * 주소가 바뀐다. 그리기만 해서는 요청이 안 나가야 한다.
 */
describe("이메일 변경 확정", () => {
  it("그리기만 해서는 보내지 않고, 누르면 토큰을 보낸다", async () => {
    render(<EmailConfirmButton token="tok" />);
    expect(api).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "새 이메일 주소로 바꾸기" }));

    await waitFor(() =>
      expect(api).toHaveBeenCalledWith("/api/me/email/confirm", { method: "POST", body: { token: "tok" } }),
    );
    expect(await screen.findByRole("status")).toHaveTextContent("이메일 주소를 바꿨습니다");
  });

  it("쓸 수 없는 링크면 다시 요청하라고 말한다", async () => {
    vi.mocked(api).mockRejectedValue(
      new ApiError(422, "tag:projectshop.example,2026:error:email-change-token-invalid", "invalid"),
    );
    render(<EmailConfirmButton token="old" />);

    fireEvent.click(screen.getByRole("button", { name: "새 이메일 주소로 바꾸기" }));

    expect(await screen.findByText(/다시 요청해 주세요/)).toBeInTheDocument();
  });
});
