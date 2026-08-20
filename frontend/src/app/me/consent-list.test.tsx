import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { ConsentList, type ConsentView } from "./consent-list";

// 화면이 조작 뒤에 부르는 것들이다. 여기서 고정하려는 것은 「무엇을 그리나」라
// 실제로 부르지 않게 막아 둔다 — 안 막으면 라우터가 없다고 터진다.
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: () => {} }) }));

function consent(overrides: Partial<ConsentView>): ConsentView {
  return {
    code: "marketing_email",
    title: "광고성 정보 수신",
    isRequired: false,
    granted: false,
    actedAt: null,
    dependsOn: null,
    ...overrides,
  };
}

/**
 * 동의 목록이 무엇을 그리나(`Q9`).
 *
 * <p><b>여기서 고정하는 것은 판단이다.</b> 필수 항목에 철회 버튼을 안 그리는 것은 취향이 아니라
 * 서버가 거부하는 조작이라서고(`5f`), 그리면 눌러야 422 가 난다(`D20`).
 */
describe("동의 목록", () => {
  it("필수 항목에는 철회 버튼 대신 탈퇴로 가는 길을 준다", () => {
    render(<ConsentList items={[consent({ isRequired: true, granted: true })]} />);

    expect(screen.queryByRole("button", { name: "동의 철회" })).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "탈퇴" })).toHaveAttribute("href", "/me/withdraw");
  });

  it("선택 항목은 켠 것과 끈 것의 버튼이 다르다", () => {
    render(
      <ConsentList
        items={[
          consent({ code: "a", title: "켠 것", granted: true }),
          consent({ code: "b", title: "끈 것", granted: false }),
        ]}
      />,
    );

    expect(screen.getByRole("button", { name: "동의 철회" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "동의하기" })).toBeEnabled();
  });

  it("부모에 동의하기 전에는 종속 항목을 못 켜고 이유를 말한다", () => {
    render(
      <ConsentList
        items={[
          consent({ code: "marketing_email", title: "이메일 수신", granted: false }),
          consent({ code: "marketing_night", title: "야간 수신", dependsOn: "marketing_email" }),
        ]}
      />,
    );

    // 부모도 안 켜진 상태라 「동의하기」가 둘이다. 막힌 것은 종속 쪽이다.
    const blocked = screen.getAllByRole("button", { name: "동의하기" })[1];

    expect(blocked).toBeDisabled();
    // 감추지 않고 이유를 적는다 — 감추면 무엇을 더 켤 수 있는지 알 방법이 없다.
    expect(blocked).toHaveAccessibleDescription(/이메일 수신에 먼저 동의/);
  });

  it("건드린 적 없는 항목은 시각을 안 그린다", () => {
    render(<ConsentList items={[consent({ actedAt: null })]} />);

    expect(screen.getByText("동의하지 않음")).toBeInTheDocument();
    expect(screen.queryByText(/·/)).not.toBeInTheDocument();
  });
});
