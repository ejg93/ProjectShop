import Link from "next/link";

import { apiSessionOptional } from "@/lib/api-session";

import { LogoutButton } from "./logout-button";

/** 로그인한 사람인지만 본다. 이름은 안 쓴다 — 머리에 이름을 그리면 폭이 사람마다 달라진다 */
type Me = { userId: number };

/**
 * 모든 화면이 쓰는 머리. 어디에 있든 상품·장바구니·계정으로 갈 수 있다.
 *
 * <p><b>로그인 여부로 항목이 갈린다</b>(`13b`). 그전까지는 누구에게나 「로그인」이 떠서,
 * 주문서까지 온 사람에게도 머리가 **로그인하라고 말하고 있었다**(`15-2` 에서 드러났다).
 *
 * <p><b>판정은 백엔드가 한다.</b> 세션 쿠키가 있는지로 가르지 않는다 — 만료된 세션도 쿠키는
 * 남아서, 그렇게 하면 「로그아웃」을 그려 놓고 누르면 로그인으로 튕긴다(`D24`).
 *
 * <p><b>안 보내는 입구로 묻는다.</b> 머리는 모든 화면에 있어서 보통 입구를 쓰면
 * 비로그인이 상품 목록만 봐도 로그인으로 튕긴다 — 공개 화면이 공개가 아니게 된다.
 *
 * <p><b>권한으로 가리는 것은 아직 없다.</b> 셀러·관리자 화면이 미착수라 걸 링크가 없다
 * (`13f`·`13g`·`16`). 그때 `/api/me/permissions` 로 가른다 — 역할 이름을 화면에 안 박는다.
 */
export async function SiteHeader() {
  const me = await apiSessionOptional<Me>("/api/me");

  return (
    <header className="border-b border-border">
      <div className="mx-auto flex h-16 max-w-6xl items-center gap-6 px-4">
        <Link href="/" className="font-semibold tracking-tight">
          ProjectShop
        </Link>

        {/* 이름을 붙인다. 화면낭독기가 여러 nav 를 구별하는 방법이 이것뿐이다 */}
        <nav aria-label="주요 메뉴" className="flex flex-1 items-center gap-5 text-sm">
          <HeaderLink href="/products">상품</HeaderLink>
          <HeaderLink href="/cart">장바구니</HeaderLink>
          {/*
            내 주문은 로그인해야 보인다. 비로그인에게 그리면 **누르는 순간 로그인으로 튕기는
            링크**가 되고, 그건 갈 곳이 있는 것처럼 보이게 하는 것이다(`D20` 「권한 없는 것은 숨긴다」).
          */}
          {me ? <HeaderLink href="/orders">내 주문</HeaderLink> : null}
        </nav>

        <div className="flex items-center gap-5">
          {me ? (
            <>
              <HeaderLink href="/me">내 정보</HeaderLink>
              <LogoutButton />
            </>
          ) : (
            <HeaderLink href="/login">로그인</HeaderLink>
          )}
        </div>
      </div>
    </header>
  );
}

function HeaderLink({ href, children }: { href: string; children: string }) {
  return (
    <Link
      href={href}
      className="
        rounded-ui text-sm text-text-muted
        transition-colors duration-200
        hover:text-text
        focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-accent-text
      "
    >
      {children}
    </Link>
  );
}
