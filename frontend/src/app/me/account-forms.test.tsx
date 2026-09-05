import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { AccountForms } from "./account-forms";

// 고친 뒤에 목록을 손으로 안 고치고 서버 컴포넌트를 다시 그린다. 여기서 볼 것은 아니다.
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: () => {} }) }));

/**
 * 계정 수정 화면이 무엇을 그리나(`D15`·`Q9`).
 *
 * <p><b>제일 중요한 판단은 「안 그리는 것」이다.</b> 지금 값을 볼 수 없으면 고치는 폼을
 * 아예 안 낸다(`D20`) — 무엇을 덮어쓰는지 모르는 채로 누르게 하지 않으려는 것이고,
 * 타입에서는 그냥 선택 인자라 <b>빠뜨려도 컴파일이 통과한다.</b>
 */
describe("계정 수정 화면", () => {
  it("지금 이름을 볼 수 없으면 이름 폼을 안 그린다", () => {
    render(<AccountForms email="a@example.com" />);

    expect(screen.queryByRole("button", { name: "이름 바꾸기" })).not.toBeInTheDocument();
    // 나머지는 그대로 있어야 한다 — 하나가 없다고 화면이 통째로 비면 안 된다.
    expect(screen.getByRole("button", { name: "이메일 바꾸기" })).toBeInTheDocument();
  });

  it("지금 이메일을 볼 수 없으면 이메일 폼을 안 그린다", () => {
    render(<AccountForms displayName="홍길동" />);

    expect(screen.queryByRole("button", { name: "이메일 바꾸기" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "이름 바꾸기" })).toBeInTheDocument();
  });

  it("비밀번호 바꾸기는 지금 값과 무관하게 늘 있다", () => {
    render(<AccountForms />);

    // 비밀번호는 「지금 값」을 화면에 내릴 수가 없다. 그래서 볼 수 있나와 상관이 없다.
    expect(screen.getByRole("button", { name: "비밀번호 바꾸기" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "이름 바꾸기" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "이메일 바꾸기" })).not.toBeInTheDocument();
  });

  it("지금 값을 폼에 미리 채워 준다", () => {
    render(<AccountForms displayName="홍길동" email="a@example.com" />);

    // 빈 칸을 주면 사용자가 지금 값을 모른 채로 덮어쓴다(`D20`).
    expect(screen.getByLabelText(/이름/)).toHaveValue("홍길동");
    expect(screen.getByLabelText(/새 이메일/)).toHaveValue("a@example.com");
  });

  it("계정을 되찾는 통로를 바꿀 때는 현재 비밀번호를 같이 받는다", () => {
    render(<AccountForms displayName="홍길동" email="a@example.com" />);

    // 이메일과 비밀번호는 세션을 훔친 사람이 바꾸면 주인이 계정을 잃는다(`5e`·`Q13`).
    // 이름은 그렇지 않아서 안 받는다 — 폼마다 다른 판단이라 한 폼으로 합치면 무너진다.
    const again = screen.getAllByLabelText(/현재 비밀번호/);
    expect(again).toHaveLength(2);
    again.forEach((field) => expect(field).toHaveAttribute("type", "password"));
  });

  it("결과를 알리는 자리가 폼마다 소리로도 전해진다", () => {
    render(<AccountForms displayName="홍길동" email="a@example.com" />);

    // 폼이 셋이라 알림 자리도 셋이다. 한 자리를 나눠 쓰면 어느 폼의 결과인지 안 들린다(`D20`).
    expect(screen.getAllByRole("status")).toHaveLength(3);
    expect(screen.getAllByRole("alert")).toHaveLength(3);
  });
});
