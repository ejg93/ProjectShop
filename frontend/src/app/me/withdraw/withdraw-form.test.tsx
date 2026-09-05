import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { api } from "@/lib/api";

import { WithdrawForm } from "./withdraw-form";

// 서버를 진짜로 부르지 않는다. `ApiError` 는 진짜를 그대로 둔다 — 화면이 그 타입으로 분기한다.
vi.mock("@/lib/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api")>()),
  api: vi.fn(),
}));

/**
 * 성공하면 `location.replace` 로 나간다. jsdom 은 실제 이동을 못 하고,
 * `location.replace` 는 **재정의가 막혀 있어** `spyOn` 이 `Cannot redefine property` 로 죽는다.
 * 그래서 `location` 자체를 갈아 끼운다.
 */
const replace = vi.fn();

beforeEach(() => {
  vi.mocked(api).mockResolvedValue(undefined);
  replace.mockClear();
  vi.stubGlobal("location", { href: "http://localhost/me/withdraw", replace });
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

/**
 * 탈퇴 화면이 무엇을 내미나(`D15`·`Q9`).
 *
 * <p><b>이 화면의 판단은 「무엇을 더 받나」와 「무엇을 안 하나」다.</b> 되돌릴 수 없는 조작인데
 * 확인 대화상자는 <b>일부러 안 쓴다</b> — 그 결정이 코드에는 <b>없는 코드</b>로만 있어서
 * 누가 「확인은 받아야지」 하고 넣어도 아무 데도 안 걸린다.
 */
describe("탈퇴 화면", () => {
  it("비밀번호를 다시 받는다", () => {
    render(<WithdrawForm />);

    // 확인 대화상자만으로는 부족하다 — 세션을 훔친 사람이 탈퇴까지 하면
    // 주인이 계정을 영영 잃는다(`D20` 「되돌릴 수 없는 조작」, `5g`).
    const password = screen.getByLabelText(/현재 비밀번호/);
    expect(password).toHaveAttribute("type", "password");
    // 비밀번호 관리자가 새 비밀번호로 오해하고 저장하지 않게 한다.
    expect(password).toHaveAttribute("autocomplete", "current-password");
  });

  it("대화상자를 겹치지 않고 곧장 보낸다", async () => {
    const confirmSpy = vi.spyOn(window, "confirm").mockReturnValue(false);

    const { container } = render(<WithdrawForm />);
    // **버튼이 아니라 폼에 건다.** 버튼에 걸면 제출이 안 일어나고 조용히 아무 일도 안 한다.
    fireEvent.submit(container.querySelector("form")!);

    // **누르는 데까지 가야 한다.** 그리기만 보면 대화상자가 붙어도 안 걸린다 —
    // 처음 판이 그래서 아무것도 안 봤다.
    await waitFor(() => expect(api).toHaveBeenCalledWith("/api/me/withdraw", expect.anything()));

    // 무엇이 사라지는지는 이 화면이 위에서 이미 말했고, 확인이 흔해지면
    // 사용자가 읽지 않고 누른다(`D20`). **없는 코드를 고정하는 자리다.**
    expect(confirmSpy).not.toHaveBeenCalled();
  });

  it("성공하면 뒤로 가기로 못 돌아오게 나간다", async () => {
    const { container } = render(<WithdrawForm />);
    // **버튼이 아니라 폼에 건다.** 버튼에 걸면 제출이 안 일어나고 조용히 아무 일도 안 한다.
    fireEvent.submit(container.querySelector("form")!);

    // `router.push` 면 뒤로 가기로 이 화면에 돌아와 없는 계정으로 한 번 더 누르게 된다.
    await waitFor(() =>
      expect(replace).toHaveBeenCalledWith("/login?reason=withdrawn"),
    );
  });

  it("실패를 알리는 자리가 소리로도 전해진다", () => {
    render(<WithdrawForm />);

    // 눈으로 보면 문구가 뜨지만 역할이 없으면 스크린 리더는 안 읽는다(`D20`).
    expect(screen.getByRole("alert")).toBeInTheDocument();
  });

  it("보내기 버튼이 처음부터 눌린 채로 있지 않다", () => {
    render(<WithdrawForm />);

    expect(screen.getByRole("button")).toBeEnabled();
  });
});
