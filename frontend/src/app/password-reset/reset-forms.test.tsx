import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError, api } from "@/lib/api";

import { ResetConfirmForm, ResetRequestForm } from "./reset-forms";

vi.mock("@/lib/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api")>()),
  api: vi.fn(),
}));

const replace = vi.fn();

beforeEach(() => {
  vi.mocked(api).mockResolvedValue(undefined);
  replace.mockClear();
  vi.stubGlobal("location", { href: "http://localhost/password-reset", replace });
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

/**
 * 비밀번호 재설정 화면(`Q180`).
 *
 * <p><b>요청 쪽은 가입 여부를 안 흘리는 것이 판단이다</b> — 서버가 같은 202 를 주는데 화면이 갈라 말하면
 * 이 칸이 가입 여부를 물어보는 도구가 된다(`D14`).
 */
describe("비밀번호 재설정", () => {
  it("요청하면 가입 여부와 무관한 같은 말을 한다", async () => {
    render(<ResetRequestForm />);

    fireEvent.change(screen.getByLabelText(/가입한 이메일/), { target: { value: "who@test.local" } });
    fireEvent.submit(screen.getByLabelText(/가입한 이메일/).closest("form")!);

    await waitFor(() =>
      expect(api).toHaveBeenCalledWith("/api/auth/password-reset", {
        method: "POST",
        body: { email: "who@test.local" },
      }),
    );
    expect(await screen.findByRole("status")).toHaveTextContent("가입된 주소라면");
  });

  it("새 비밀번호를 토큰과 같이 보내고, 성공하면 뒤로 못 돌아오게 로그인으로 간다", async () => {
    render(<ResetConfirmForm token="t0k3n" />);

    fireEvent.change(screen.getByLabelText(/새 비밀번호/), { target: { value: "tangerine-kite7-violet" } });
    fireEvent.submit(screen.getByLabelText(/새 비밀번호/).closest("form")!);

    await waitFor(() =>
      expect(api).toHaveBeenCalledWith("/api/auth/password-reset/confirm", {
        method: "POST",
        body: { token: "t0k3n", newPassword: "tangerine-kite7-violet" },
      }),
    );
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/login?reason=password-reset"));
  });

  it("쓸 수 없는 링크면 다시 받으라고 말한다", async () => {
    vi.mocked(api).mockRejectedValue(
      new ApiError(422, "tag:projectshop.example,2026:error:password-reset-token-invalid", "invalid"),
    );
    render(<ResetConfirmForm token="old" />);

    fireEvent.change(screen.getByLabelText(/새 비밀번호/), { target: { value: "tangerine-kite7-violet" } });
    fireEvent.submit(screen.getByLabelText(/새 비밀번호/).closest("form")!);

    expect(await screen.findByText(/재설정 링크를 다시 받아 주세요/)).toBeInTheDocument();
    expect(replace).not.toHaveBeenCalled();
  });
});
