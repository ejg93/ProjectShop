import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

import { expectNoAxeViolations } from "@/test/axe";

import Forbidden from "./forbidden";

/**
 * 403 이 <b>고장이 아니라 권한 문제로 보이나</b>(`Q133`).
 *
 * <p><b>화면과 배선을 같이 잰다.</b> 화면만 재면 그것이 실제로 뜨는지를 모르고, 배선만 재면
 * 뜬 화면이 무엇을 말하는지를 모른다 — 이 청크의 닫힘은 둘 다여야 성립한다.
 *
 * <p><b>배선을 원문으로 재는 이유</b>는 `forbidden()` 이 Next 런타임 안에서만 도는 신호라서다.
 * {@code api-session.test.ts} 가 그것을 목으로 잡는데, <b>목은 실험 플래그가 꺼진 것을 모른다</b> —
 * 플래그가 빠지면 `forbidden()` 이 그냥 터지고 403 이 다시 {@code error.tsx} 로 떨어지는데
 * 목 시험은 초록인 채다. 같은 수를 {@code admin/audit/detail-keys.test.ts} 가 쓴다.
 */
const CONFIG = readFileSync(resolve(import.meta.dirname, "../../next.config.ts"), "utf8");
const SESSION = readFileSync(resolve(import.meta.dirname, "../lib/api-session.ts"), "utf8");

describe("권한 없음 화면", () => {
  it("권한이 없다고 말한다", () => {
    render(<Forbidden />);

    expect(
      screen.getByRole("heading", { name: "이 화면을 보실 권한이 없습니다" }),
    ).toBeInTheDocument();
  });

  it("「다시 시도」가 없다", () => {
    // 눌러도 같은 답이 온다. 두면 사용자가 몇 번 누르고 나서야 안 된다는 것을 안다.
    render(<Forbidden />);

    expect(screen.queryByRole("button", { name: /다시 시도/ })).toBeNull();
  });

  it("오류 번호를 안 띄운다", () => {
    // 되짚을 사고가 아니라 정상 동작이고, 판정은 백엔드 감사 기록에 남는다(`D16`).
    const { container } = render(<Forbidden />);

    expect(container.textContent).not.toMatch(/오류 번호/);
  });

  it("무엇이 막혔는지는 안 적는다", () => {
    // 「관리자 화면입니다」는 그 주소에 무엇이 있는지 알려 주는 셈이다(`D14`).
    const { container } = render(<Forbidden />);

    expect(container.textContent).not.toMatch(/관리자|감사|셀러/);
  });

  it("접근성 위반이 없다", async () => {
    const { container } = render(<Forbidden />);

    await expectNoAxeViolations(container);
  });
});

describe("권한 없음 화면으로 가는 배선", () => {
  it("실험 플래그가 켜져 있다", () => {
    // 이것이 꺼지면 `forbidden()` 이 그냥 터지고 403 이 다시 `error.tsx` 로 떨어진다.
    // Next 를 올릴 때 이 플래그가 살아 있는지 보라는 표시이기도 하다.
    expect(CONFIG).toMatch(/authInterrupts:\s*true/);
  });

  it("세션 입구가 403 에서 그것을 부른다", () => {
    // 화면마다 `try/catch` 로 잡으면 열일곱 곳이 각자 기억해야 한다(`Q133`).
    expect(SESSION).toMatch(/status === 403\)\s*\{\s*forbidden\(\);/);
  });
});
