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
    apiSessionOptional.mockResolvedValue({ userId: 7, permissions: [] });

    render(await SiteHeader());

    expect(screen.getByRole("button", { name: "로그아웃" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "내 주문" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "내 정보" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "로그인" })).not.toBeInTheDocument();
  });

  it("셀러 화면 링크는 그 권한이 있어야 보인다", async () => {
    apiSessionOptional.mockResolvedValue({
      userId: 7,
      permissions: [{ resource: "order", action: "update_status", scopes: ["SELLER"] }],
    });

    render(await SiteHeader());

    // **역할 이름으로 안 가른다**(`D24`). 이 화면이 하는 일이 `order:update_status` 라
    // 그 권한을 가진 사람에게만 갈 곳이 있다.
    expect(screen.getByRole("link", { name: "받은 주문" })).toBeInTheDocument();
  });

  it("사는 사람에게는 셀러 화면 링크가 없다", async () => {
    apiSessionOptional.mockResolvedValue({
      userId: 7,
      permissions: [{ resource: "order", action: "read", scopes: ["OWN"] }],
    });

    render(await SiteHeader());

    // 누르면 튕기는 링크는 갈 곳이 있는 것처럼 보이게 하는 것이다(`D20`).
    expect(screen.queryByRole("link", { name: "받은 주문" })).not.toBeInTheDocument();
  });

  it("정산서 링크는 읽기 권한이 있어야 보인다", async () => {
    apiSessionOptional.mockResolvedValue({
      userId: 7,
      permissions: [{ resource: "settlement", action: "read", scopes: ["SELLER"] }],
    });

    render(await SiteHeader());

    // 셀러와 관리자·감사자가 같은 링크를 쓴다(`20-1`). 보는 것이 같고 범위만 달라서다.
    expect(screen.getByRole("link", { name: "정산서" })).toBeInTheDocument();
  });

  it("사는 사람에게는 정산서 링크가 없다", async () => {
    apiSessionOptional.mockResolvedValue({
      userId: 7,
      permissions: [{ resource: "order", action: "read", scopes: ["OWN"] }],
    });

    render(await SiteHeader());

    // 정산은 우리와 셀러 사이의 계산이라 사는 사람은 이 자원에 권한이 없다(`V56`).
    expect(screen.queryByRole("link", { name: "정산서" })).not.toBeInTheDocument();
  });


  it("감사 기록 링크는 그 권한이 있어야 보인다", async () => {
    apiSessionOptional.mockResolvedValue({
      userId: 7,
      permissions: [{ resource: "audit", action: "read", scopes: ["ALL"] }],
    });

    render(await SiteHeader());

    // **관리자에게 갈 화면이 여기 하나뿐이다**(`Q132`). 이 링크가 없으면 관리자로 들어온
    // 사람이 자기 권한에 닿을 입구를 화면에서 못 찾는다.
    expect(screen.getByRole("link", { name: "감사 기록" })).toBeInTheDocument();
  });

  it("사는 사람에게는 감사 기록 링크가 없다", async () => {
    apiSessionOptional.mockResolvedValue({
      userId: 7,
      permissions: [{ resource: "order", action: "read", scopes: ["OWN"] }],
    });

    render(await SiteHeader());

    // 남이 무엇을 했는지는 사는 사람도 파는 사람도 볼 것이 아니다(`V12`).
    expect(screen.queryByRole("link", { name: "감사 기록" })).not.toBeInTheDocument();
  });
  it("후기 신고 처리는 관리자에게만, 받은 후기는 셀러 사람에게만 보인다", async () => {
    // 사는 사람은 신고를 할 수 있지만(`review:report`) 처리할 수는 없다 — 링크가 없어야 한다(`Q171`).
    apiSessionOptional.mockResolvedValue({
      userId: 7,
      permissions: [{ resource: "review", action: "report", scopes: ["ALL"] }],
    });
    render(await SiteHeader());
    expect(screen.queryByRole("link", { name: "후기 신고" })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "받은 후기" })).not.toBeInTheDocument();
  });

  it("후기 신고 링크는 처리 권한이 있어야 보인다", async () => {
    apiSessionOptional.mockResolvedValue({
      userId: 7,
      permissions: [{ resource: "review", action: "moderate", scopes: ["ALL"] }],
    });
    render(await SiteHeader());
    expect(screen.getByRole("link", { name: "후기 신고" })).toBeInTheDocument();
  });

  it("대행 중이면 맨 위에 띠와 끝내기 버튼이 있다", async () => {
    // 관리자가 지금 누구로 보고 있는지를 잊으면 그 화면을 자기 것으로 읽는다(`16b`).
    apiSessionOptional.mockResolvedValue({ userId: 8, permissions: [], impersonatedBy: 1 });
    render(await SiteHeader());
    expect(screen.getByRole("status")).toHaveTextContent("다른 사용자의 화면을 보는 중입니다");
    expect(screen.getByRole("button", { name: "대행 끝내기" })).toBeInTheDocument();
  });

  it("대행 중이 아니면 띠가 없다", async () => {
    apiSessionOptional.mockResolvedValue({ userId: 8, permissions: [] });
    render(await SiteHeader());
    expect(screen.queryByRole("button", { name: "대행 끝내기" })).not.toBeInTheDocument();
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
