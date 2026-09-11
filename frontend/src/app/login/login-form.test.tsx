import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError, api } from "@/lib/api";

import { LoginForm } from "./login-form";

// 서버를 진짜로 부르지 않는다. `ApiError` 는 진짜를 그대로 둔다 — 화면이 그 타입으로 분기한다.
vi.mock("@/lib/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api")>()),
  api: vi.fn(),
}));

/** `slug` 는 `type` 에서 접두어를 떼어 만든다. 여기서 문자열을 손으로 만들면 그 계산을 건너뛴다. */
const errorType = (slug: string) => `tag:projectshop.example,2026:error:${slug}`;

/**
 * 성공하면 `location.replace` 로 통째로 이동한다. jsdom 은 실제 이동을 못 하고
 * `location.replace` 는 재정의가 막혀 있어 `spyOn` 이 `Cannot redefine property` 로 죽는다.
 */
const replace = vi.fn();

beforeEach(() => {
  // `restoreAllMocks` 는 `vi.mock` 공장이 만든 `vi.fn()` 의 호출 기록을 안 지운다 —
  // 안 지우면 호출 횟수를 세는 단언이 앞 테스트까지 같이 센다.
  vi.mocked(api).mockClear();
  vi.mocked(api).mockResolvedValue({ userId: 1, email: "a@b.local" });
  replace.mockClear();
  vi.stubGlobal("location", { href: "http://localhost/login", replace });
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const submitForm = (container: HTMLElement) =>
  // **버튼이 아니라 폼에 건다.** 버튼에 걸면 제출이 안 일어나고 조용히 아무 일도 안 한다(`D15`).
  fireEvent.submit(container.querySelector("form")!);

/**
 * 로그인 화면이 무엇을 고르나(`Q20`).
 *
 * <p><b>이 화면의 판단은 「어느 오류를 무슨 말로 옮기나」와 「언제 안 보내나」다.</b>
 * 둘 다 빌드·타입 검사·린트를 통과하면서 틀릴 수 있다.
 */
describe("로그인 화면", () => {
  describe("서버 오류를 화면 문구로 옮길 때", () => {
    it("맞지 않는 로그인은 계정이 있는지를 안 알려준다", async () => {
      vi.mocked(api).mockRejectedValue(
        new ApiError(401, errorType("login-failed"), "credentials do not match"),
      );

      const { container } = render(<LoginForm />);
      submitForm(container);

      // 「없는 계정」과 「틀린 비밀번호」를 가르면 **가입 여부를 물어보는 도구**가 된다(`D14`).
      const alert = await screen.findByRole("alert");
      expect(alert).toHaveTextContent("이메일 또는 비밀번호가 맞지 않습니다.");
      expect(alert.textContent).not.toMatch(/계정|가입|비밀번호가 틀/);
    });

    it("모르는 슬러그는 기본 문구로 떨어진다", async () => {
      // 백엔드가 슬러그를 새로 만들면 화면은 이 가지로 온다. **여기서 죽으면 안 된다** —
      // 어느 오류가 새로 생길지 화면이 미리 알 수 없다.
      vi.mocked(api).mockRejectedValue(
        new ApiError(403, errorType("account-locked"), "locked"),
      );

      const { container } = render(<LoginForm />);
      submitForm(container);

      expect(await screen.findByRole("alert")).toHaveTextContent(
        "로그인하지 못했습니다. 잠시 후 다시 시도해 주세요.",
      );
    });

    it("서버 문구를 그대로 내보내지 않는다", async () => {
      vi.mocked(api).mockRejectedValue(
        new ApiError(401, errorType("login-failed"), "credentials do not match"),
      );

      const { container } = render(<LoginForm />);
      submitForm(container);

      // 서버 `detail` 은 개발자가 읽는 평서형이고 영문이다(`D20`).
      expect(await screen.findByRole("alert")).not.toHaveTextContent("credentials do not match");
    });

    it("응답이 `problem+json` 이 아니어도 말을 한다", async () => {
      // 프록시가 안 붙거나 서버가 죽으면 HTML 이 온다. `ApiError` 가 아닌 것이 던져지는 자리고,
      // 여기서 분기를 빠뜨리면 **아무 문구도 안 뜨고 버튼만 풀린다.**
      vi.mocked(api).mockRejectedValue(new TypeError("Failed to fetch"));

      const { container } = render(<LoginForm />);
      submitForm(container);

      expect(await screen.findByRole("alert")).toHaveTextContent(
        "서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.",
      );
    });
  });

  describe("보내는 동안", () => {
    it("버튼을 잠가 두 번 안 보낸다", async () => {
      // 응답이 올 때까지 붙잡는다. 그동안 버튼이 풀려 있으면 중복 로그인 시도가 나간다.
      let release!: (value: { userId: number; email: string }) => void;
      vi.mocked(api).mockReturnValue(new Promise((resolve) => { release = resolve; }));

      const { container } = render(<LoginForm />);
      expect(screen.getByRole("button")).toBeEnabled();

      submitForm(container);

      // 버튼을 미리 잡아 두지 않는다 — 다시 그리면 잡아 둔 노드가 화면 밖의 것이 된다.
      await waitFor(() => expect(screen.getByRole("button")).toBeDisabled());
      // 문구도 같이 바뀐다 — 잠긴 이유를 말 안 하면 고장으로 본다(`D20`).
      expect(screen.getByRole("button")).toHaveTextContent("확인하는 중");

      release({ userId: 1, email: "a@b.local" });
    });

    it("실패하면 다시 누를 수 있게 푼다", async () => {
      vi.mocked(api).mockRejectedValue(
        new ApiError(401, errorType("login-failed"), "credentials do not match"),
      );

      const { container } = render(<LoginForm />);
      submitForm(container);

      // `13-2a` 가 그 반대를 실물로 밟았다 — 버튼이 「확인하는 중」에 멈춰서
      // 사용자 눈에는 아무 일도 안 난 것으로 보였다.
      await waitFor(() => expect(screen.getByRole("button")).toBeEnabled());
      expect(screen.getByRole("button")).toHaveTextContent("로그인");
    });
  });

  describe("성공하면", () => {
    it("뒤로 가기로 안 돌아오게 통째로 이동한다", async () => {
      const { container } = render(<LoginForm />);
      submitForm(container);

      // 세션 쿠키가 `HttpOnly` 라 서버 컴포넌트가 전부 다시 그려져야 로그인한 사실이 반영된다.
      // 클라이언트 이동으로 바꾸면 **머리글이 로그인 전 상태로 남는다.**
      await waitFor(() => expect(replace).toHaveBeenCalledWith("/"));
    });

    it("앞선 오류 문구를 지운다", async () => {
      vi.mocked(api).mockRejectedValueOnce(
        new ApiError(401, errorType("login-failed"), "credentials do not match"),
      );

      const { container } = render(<LoginForm />);
      submitForm(container);
      await screen.findByRole("alert");

      vi.mocked(api).mockResolvedValue({ userId: 1, email: "a@b.local" });
      // 앞 제출이 끝나 버튼이 풀린 뒤라야 두 번째가 나간다.
      await waitFor(() => expect(screen.getByRole("button")).toBeEnabled());
      submitForm(container);

      await waitFor(() => expect(api).toHaveBeenCalledTimes(2));
      // **남는 쪽도 같이 본다**(`D15` 「조용히 실패하는 자리 셋」) — 안 지우면
      // 성공한 뒤에도 「맞지 않습니다」가 화면에 남는다.
      await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument());
    });
  });
});
