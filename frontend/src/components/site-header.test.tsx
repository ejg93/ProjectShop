import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

// `vi.mock` 은 파일 맨 위로 끌어올려진다. 그래서 공장 안에서 바깥 변수를 못 읽고,
// `vi.hoisted` 로 그 변수를 같이 끌어올린다.
const { apiSessionOptional } = vi.hoisted(() => ({ apiSessionOptional: vi.fn() }));

vi.mock("@/lib/api-session", () => ({ apiSessionOptional }));

import { SiteHeader } from "./site-header";

/**
 * 머리가 로그인 여부로 갈리나(`13b`).
 *
 * <p><b>그전까지 누구에게나 「로그인」이 떴다.</b> 주문서까지 온 사람에게도 머리가
 * 로그인하라고 말하고 있었다(`15-2` 에서 드러났다).
 *
 * <p>서버 컴포넌트라 {@code await} 한 결과를 그린다 — Testing Library 가 그것을 받는다.
 */
describe("셸의 머리", () => {
  it("로그인 전에는 로그인 링크만 있다", async () => {
    apiSessionOptional.mockResolvedValue(null);

    render(await SiteHeader());

    expect(screen.getByRole("link", { name: "로그인" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "로그아웃" })).not.toBeInTheDocument();
    // 누르면 로그인으로 튕기는 링크를 안 그린다(`D20` 「권한 없는 것은 숨긴다」).
    expect(screen.queryByRole("link", { name: "내 주문" })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "내 정보" })).not.toBeInTheDocument();
  });

  it("로그인하면 로그아웃과 내 주문이 생긴다", async () => {
    apiSessionOptional.mockResolvedValue({ userId: 7 });

    render(await SiteHeader());

    expect(screen.getByRole("button", { name: "로그아웃" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "내 주문" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "내 정보" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "로그인" })).not.toBeInTheDocument();
  });

  it("상품과 장바구니는 로그인 전에도 있다", async () => {
    apiSessionOptional.mockResolvedValue(null);

    render(await SiteHeader());

    // 비로그인이 공개 화면을 볼 때 머리가 로그인으로 튕기면 안 된다 —
    // `apiSessionOptional` 이 401 을 null 로 돌려주는 이유가 이것이다.
    expect(screen.getByRole("link", { name: "상품" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "장바구니" })).toBeInTheDocument();
  });
});
