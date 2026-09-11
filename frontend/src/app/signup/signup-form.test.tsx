import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { expectNoAxeViolations } from "@/test/axe";

import { api } from "@/lib/api";

import { SignupForm, type ConsentItem } from "./signup-form";

vi.mock("@/lib/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api")>()),
  api: vi.fn(),
}));

/**
 * 항목은 서버가 준다(`13d-1`). 화면에 코드를 박지 않으므로 테스트도 박지 않는다 —
 * <b>이 목록이 바뀌어도 화면 판단은 같아야 한다.</b>
 */
/** 고지 넷은 이 화면의 판단과 무관해서 비워 둔다 — 서버가 준 값을 그대로 그린다 */
const item = (
  code: string,
  title: string,
  isRequired: boolean,
  dependsOn: string | null = null,
): ConsentItem => ({
  code,
  title,
  version: 1,
  isRequired,
  purpose: null,
  collectedItems: null,
  retentionPeriod: null,
  refusalDisadvantage: null,
  dependsOn,
});

const ITEMS: ConsentItem[] = [
  item("tos", "이용약관", true),
  item("privacy", "개인정보 수집·이용", true),
  item("marketing", "광고성 정보 수신", false),
  // 야간 수신은 광고 수신에 매달린다(`D2` R14). 부모를 끄면 같이 꺼져야 한다.
  item("marketing-night", "야간 광고 수신", false, "marketing"),
];

const BODIES = Object.fromEntries(ITEMS.map((item) => [item.code, <p key={item.code}>본문</p>]));

const replace = vi.fn();

beforeEach(() => {
  vi.mocked(api).mockClear();
  vi.mocked(api).mockResolvedValue({ userId: 1 });
  replace.mockClear();
  vi.stubGlobal("location", { href: "http://localhost/signup", replace });
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const renderForm = () => render(<SignupForm items={ITEMS} bodies={BODIES} />);

const submitForm = (container: HTMLElement) =>
  // **버튼이 아니라 폼에 건다**(`D15`).
  fireEvent.submit(container.querySelector("form")!);

const check = (title: string | RegExp) =>
  fireEvent.click(screen.getByLabelText(title));

/**
 * 가입 화면이 무엇을 막고 무엇을 보내나(`Q20`).
 *
 * <p><b>이 화면의 판단 셋이 전부 법에서 왔다</b> — 필수 동의 없이 못 보내는 것,
 * 부모를 끄면 종속도 꺼지는 것(`D2` R14), 안 건드린 선택 항목도 거절로 보내는 것.
 */
describe("가입 화면", () => {
  describe("필수 동의가 빠지면", () => {
    it("버튼이 안 눌린다", () => {
      renderForm();

      // 누를 수 있게 두고 422 를 받으면 왕복이 헛돈다.
      expect(screen.getByRole("button", { name: /가입하기/ })).toBeDisabled();
    });

    it("왜 못 누르는지 글로 말한다", () => {
      renderForm();

      // 이유 없이 회색이면 고장으로 본다(`D20`).
      expect(screen.getByText(/필수 항목에 동의하셔야/)).toBeInTheDocument();
    });

    it("필수를 다 켜면 눌린다", () => {
      renderForm();

      check(/이용약관/);
      check(/개인정보 수집·이용/);

      // **남는 쪽도 같이 본다**(`D15`) — 「안 눌린다」만 보면 영영 안 눌려도 초록이다.
      expect(screen.getByRole("button", { name: /가입하기/ })).toBeEnabled();
      expect(screen.queryByText(/필수 항목에 동의하셔야/)).not.toBeInTheDocument();
    });
  });

  describe("종속 항목은", () => {
    it("부모에 동의하기 전에는 못 켠다", () => {
      renderForm();

      const night = screen.getByLabelText(/야간 광고 수신/);
      expect(night).toBeDisabled();
      // 숨기면 무엇을 더 켤 수 있는지 알 방법이 없다. 보이되 이유를 적는다.
      expect(screen.getByText(/광고성 정보 수신에 먼저 동의하셔야/)).toBeInTheDocument();
      // 이유가 눈에만 있으면 화면낭독기가 안 읽는다(`D20`).
      expect(night).toHaveAttribute("aria-describedby");
    });

    it("부모를 끄면 같이 꺼진다", () => {
      renderForm();

      check(/광고성 정보 수신/);
      check(/야간 광고 수신/);
      expect(screen.getByLabelText(/야간 광고 수신/)).toBeChecked();

      check(/광고성 정보 수신/);

      // 서버가 어차피 같이 거두지만, 켜진 채로 두면 **보낼 수 없는 동의가 켜져 보인다**(`D2` R14).
      expect(screen.getByLabelText(/야간 광고 수신/)).not.toBeChecked();
    });
  });

  describe("보낼 때", () => {
    it("안 건드린 선택 항목도 거절로 보낸다", async () => {
      const { container } = renderForm();

      check(/이용약관/);
      check(/개인정보 수집·이용/);
      submitForm(container);

      // 서버가 **거절(행 있음)** 과 **안 건드림(행 없음)** 을 가르는데, 가입 화면은
      // 전부를 물었으므로 안 건드린 것이 없다. 빠뜨리면 나중에 다시 물어야 한다.
      await waitFor(() => expect(api).toHaveBeenCalled());
      const body = vi.mocked(api).mock.calls[0][1]!.body as { consents: Record<string, boolean> };
      expect(body.consents).toEqual({
        tos: true,
        privacy: true,
        marketing: false,
        "marketing-night": false,
      });
    });

    it("가입은 로그인이 아니라 로그인 화면으로 보낸다", async () => {
      const { container } = renderForm();

      check(/이용약관/);
      check(/개인정보 수집·이용/);
      submitForm(container);

      // 서버가 세션을 안 만들고 `userId` 만 준다(`5-2`). 홈으로 보내면 로그아웃 상태의 홈이다.
      // **`reason` 이름이 갈리면 로그인 화면이 가입한 사람에게 아무 말도 안 한다**(`13e` 가 밟았다).
      await waitFor(() => expect(replace).toHaveBeenCalledWith("/login?reason=signed-up"));
    });
  });

  /**
   * <b>`jsx-a11y` 가 못 보는 자리를 본다</b>(`Q21`). 그쪽은 정적이라 JSX 에 적힌 것만 읽고,
   * 여기서 검사하는 것은 <b>켜고 끄면서 생겨난 DOM</b> 이다 — 잠긴 체크상자와 그 이유가
   * `aria-describedby` 로 실제로 이어졌는지는 그리고 나서야 알 수 있다.
   */
  it("켜고 끄는 사이에도 접근성 위반이 없다", async () => {
    const { container } = renderForm();

    // 처음 판: 종속 항목이 잠겨 있고 이유가 붙어 있다.
    await expectNoAxeViolations(container);

    check(/광고성 정보 수신/);

    // 부모를 켠 판: 잠금이 풀리고 이유 문단이 사라진다. **사라진 쪽도 본다**(`D15`).
    await expectNoAxeViolations(container);
  });

  it("비밀번호 규칙을 입력칸 옆에서 말한다", () => {
    renderForm();

    // 규칙을 안 적고 422 로 알리면, 사용자가 무엇을 고쳐야 하는지 모른 채 되돌아온다.
    // **값이 `@Password` 와 같은지는 여기서 안 본다** — 그 대조는 백엔드 몫이고(`Q20-2` 가 세운다)
    // 실물 목록이 거기 있어서 화면 테스트로는 **같은 틀린 값을 쓰면 초록**이다(`D15`).
    expect(screen.getByText(/15자 이상 64자 이하/)).toBeInTheDocument();
  });
});
