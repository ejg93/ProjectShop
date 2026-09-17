import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { SiteFooter } from "./site-footer";

/**
 * 셸 바닥이 법정 표시로 가는 길을 든다(`R26` 5호, 전자상거래법 제10조제1항).
 *
 * **여기서 재는 것은 길이지 내용이 아니다.** 약관 본문은 `policy_document` 가 들고
 * 그쪽은 `PolicyQueryTest` 가 잰다 — 문안을 두 벌로 두면 한쪽만 고치는 날이 온다.
 *
 * **1~4호(상호·대표자·주소·전화)는 아직 없다.** 요건표의 그 행이 미착수로 남아 있고,
 * 서는 청크가 이 파일에 줄을 더한다.
 */
describe("셸 바닥", () => {
  it("이용약관으로 가는 길이 있다", () => {
    render(<SiteFooter />);

    expect(screen.getByRole("link", { name: "이용약관" })).toHaveAttribute("href", "/terms");
  });

  it("개인정보처리방침과 청약철회 안내도 같이 든다", () => {
    render(<SiteFooter />);

    expect(screen.getByRole("link", { name: "개인정보처리방침" })).toHaveAttribute(
      "href",
      "/privacy",
    );
    expect(screen.getByRole("link", { name: "청약철회 안내" })).toHaveAttribute(
      "href",
      "/withdrawal-guide",
    );
  });
});
