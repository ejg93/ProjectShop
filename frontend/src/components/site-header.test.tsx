import { render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { redirect } from "next/navigation";

import { ApiError } from "@/lib/api";
import { expectNoAxeViolations } from "@/test/axe";

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

  /**
   * 역할 링크는 줄을 나눈다(`Q213`). 관리자는 링크가 열일곱이라 한 줄에 두면 글자 단위로 꺾였다 —
   * 첫 줄은 쇼핑·계정만 두고 판매·관리 링크는 이름 붙은 줄로 모은다.
   */
  it("판매·관리 링크는 이름 붙은 제 줄에 모이고 첫 줄에 안 섞인다", async () => {
    // 첫 줄에는 쇼핑·계정만 있다 — 역할 링크가 한 줄에 몰리면 관리자 머리가 글자 단위로 꺾였다.
    apiSessionOptional.mockResolvedValue({
      userId: 1,
      permissions: [
        { resource: "order", action: "update_status", scopes: ["ALL"] },
        { resource: "audit", action: "read", scopes: ["ALL"] },
        { resource: "payment", action: "refund", scopes: ["ALL"] },
      ],
    });

    const { container } = render(await SiteHeader());

    const selling = screen.getByRole("navigation", { name: "판매" });
    const managing = screen.getByRole("navigation", { name: "관리" });
    expect(within(selling).getByRole("link", { name: "받은 주문" })).toBeInTheDocument();
    expect(within(managing).getByRole("link", { name: "감사 기록" })).toBeInTheDocument();
    expect(within(managing).getByRole("link", { name: "환불 처리" })).toBeInTheDocument();

    // 첫 줄은 로고가 든 줄이다. 그 안의 링크가 쇼핑·계정뿐이고 역할 줄은 그 밖에 있다.
    const firstRow = screen.getByRole("link", { name: "ProjectShop" }).parentElement!;
    expect(within(firstRow).getAllByRole("link").map((link) => link.textContent)).toEqual(
      ["ProjectShop", "상품", "장바구니", "내 주문", "내 정보"],
    );
    expect(firstRow).not.toContainElement(selling);
    expect(firstRow).not.toContainElement(managing);
    await expectNoAxeViolations(container);
  });

  it("사는 사람에게는 역할 줄이 없다 — 머리가 한 줄이다", async () => {
    apiSessionOptional.mockResolvedValue({
      userId: 7,
      permissions: [{ resource: "order", action: "read", scopes: ["OWN"] }],
    });

    render(await SiteHeader());

    // 빈 줄을 그리면 이름표만 있는 메뉴가 생긴다 — 갈 곳이 없는데 있는 것처럼 보인다.
    expect(screen.queryByRole("navigation", { name: "판매" })).not.toBeInTheDocument();
    expect(screen.queryByRole("navigation", { name: "관리" })).not.toBeInTheDocument();
  });

  it("웹훅 링크는 셀러 범위로 받은 사람에게만 보인다", async () => {
    apiSessionOptional.mockResolvedValue({
      userId: 7,
      permissions: [{ resource: "webhook", action: "manage", scopes: ["SELLER"] }],
    });
    render(await SiteHeader());
    expect(screen.getByRole("link", { name: "웹훅" })).toBeInTheDocument();
  });

  it("웹훅 링크는 권한이 없거나 관리자 범위뿐이면 없다", async () => {
    apiSessionOptional.mockResolvedValue({ userId: 7, permissions: [] });
    const { unmount } = render(await SiteHeader());
    expect(screen.queryByRole("link", { name: "웹훅" })).not.toBeInTheDocument();
    unmount();

    // 관리자는 보고 지우기만 `ALL` 로 갖는다(`V114`) — 걸기(`manage`)가 없고 이 화면에는 고를 셀러도 없다(`Q175`·`Q211`).
    apiSessionOptional.mockResolvedValue({
      userId: 1,
      permissions: [
        { resource: "webhook", action: "read", scopes: ["ALL"] },
        { resource: "webhook", action: "delete", scopes: ["ALL"] },
      ],
    });
    render(await SiteHeader());
    expect(screen.queryByRole("link", { name: "웹훅" })).not.toBeInTheDocument();
  });

  it("멤버 링크는 셀러 범위로 받은 사람에게만 보인다", async () => {
    apiSessionOptional.mockResolvedValue({
      userId: 7,
      permissions: [{ resource: "seller_member", action: "read", scopes: ["SELLER"] }],
    });
    render(await SiteHeader());
    expect(screen.getByRole("link", { name: "멤버" })).toBeInTheDocument();
  });

  it("멤버 링크는 관리자 범위뿐이면 없다", async () => {
    // 관리자·감사자는 `ALL` 로 갖지만 이 화면은 속한 셀러의 첫째를 보여서 누르면 빈 화면이다(`Q208`).
    apiSessionOptional.mockResolvedValue({
      userId: 1,
      permissions: [{ resource: "seller_member", action: "read", scopes: ["ALL"] }],
    });
    render(await SiteHeader());
    expect(screen.queryByRole("link", { name: "멤버" })).not.toBeInTheDocument();
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

  it("주문 조회 링크는 주문 읽기가 전체 범위일 때만 보인다", async () => {
    // 같은 `order:read` 라도 범위가 갈린다(`Q176`) — 산 사람(OWN)·셀러(SELLER)가 누르면 403 인 화면이다.
    apiSessionOptional.mockResolvedValue({
      userId: 7,
      permissions: [{ resource: "order", action: "read", scopes: ["OWN", "SELLER"] }],
    });
    const { unmount } = render(await SiteHeader());
    expect(screen.queryByRole("link", { name: "주문 조회" })).not.toBeInTheDocument();
    unmount();

    apiSessionOptional.mockResolvedValue({
      userId: 7,
      permissions: [{ resource: "order", action: "read", scopes: ["ALL"] }],
    });
    render(await SiteHeader());
    expect(screen.getByRole("link", { name: "주문 조회" })).toHaveAttribute("href", "/admin/orders");
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

  /**
   * 백엔드가 죽어도 셸이 선다(`Q263`). 머리는 루트 레이아웃에 있어서 여기서 던지면 모든 화면이
   * 본문 없는 500 이었다 — 로그인 화면까지(`68` 실험 C).
   */
  it("백엔드에 못 닿으면 모르는 사람의 머리를 그린다", async () => {
    // Node 의 `fetch` 가 연결 실패에 던지는 모양 그대로다 — 원인에 시스템 오류 코드가 붙는다(2026-10-12 실측).
    apiSessionOptional.mockRejectedValue(
      new TypeError("fetch failed", { cause: Object.assign(new Error("connect ECONNREFUSED"), { code: "ECONNREFUSED" }) }),
    );

    const { container } = render(await SiteHeader());

    expect(screen.getByRole("link", { name: "로그인" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "로그아웃" })).not.toBeInTheDocument();
    await expectNoAxeViolations(container);
  });

  it("백엔드가 답한 오류는 덮지 않고 던진다", async () => {
    // 답을 했다는 것은 머리가 덮을 일이 아니라는 뜻이다(`D24`) — 오류 경계가 받는다.
    apiSessionOptional.mockRejectedValue(
      new ApiError(503, "tag:projectshop.example,2026:error:service-unavailable", "바깥 의존에 닿지 못했다"),
    );

    await expect(SiteHeader()).rejects.toBeInstanceOf(ApiError);
  });

  it("연결 실패가 아닌 것도 덮지 않고 던진다", async () => {
    // 시간 초과·응답을 못 읽은 것·코드 결함까지 비로그인으로 그리면 고장이 안 보인다(마무리 61차 독립 리뷰).
    apiSessionOptional.mockRejectedValue(new SyntaxError("Unexpected token < in JSON"));

    await expect(SiteHeader()).rejects.toBeInstanceOf(SyntaxError);
  });

  it("Next 가 흐름을 바꾸려 던진 것은 덮지 않는다", async () => {
    // redirect·동적 렌더 신호를 머리가 삼키면 그 화면이 엉뚱하게 굳는다 — `unstable_rethrow` 를 지킨다(PR #99 리뷰).
    let signal: unknown;
    try {
      redirect("/login");
    } catch (thrown) {
      signal = thrown;
    }
    apiSessionOptional.mockRejectedValue(signal);

    await expect(SiteHeader()).rejects.toBe(signal);
  });

  it("원인 없는 TypeError 는 코드 결함이라 던진다", async () => {
    // `undefined` 의 속성을 읽는 결함도 TypeError 다. 타입만 보면 비로그인 머리로 덮인다(PR #99 리뷰).
    apiSessionOptional.mockRejectedValue(new TypeError("Cannot read properties of undefined"));

    await expect(SiteHeader()).rejects.toBeInstanceOf(TypeError);
  });
});
