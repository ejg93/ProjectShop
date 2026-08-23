import Link from "next/link";

import { apiSessionOptional } from "@/lib/api-session";

import { LogoutButton } from "./logout-button";

/**
 * 로그인한 사람인지와, 무엇을 할 수 있는지.
 *
 * <p><b>이름은 안 쓴다</b> — 머리에 이름을 그리면 폭이 사람마다 달라진다.
 *
 * <p>권한 목록은 <b>근사치다</b>({@code PermissionCatalog}). 여기 뜬다고 그 자원을 만질 수
 * 있는 것이 아니라 실제 판정은 만질 때 다시 한다 — 화면이 <b>링크를 보일지</b> 정하는 데만 쓴다.
 */
type Me = { userId: number; permissions: Permission[] };

type Permission = { resource: string; action: string; scopes: string[] };

/**
 * 셀러 화면에 갈 수 있나.
 *
 * <p><b>역할 이름을 안 본다</b>(`D24` 「화면은 역할 이름을 모른다」). 역할로 가르면
 * 판정이 두 벌이 되고, 역할을 하나 늘릴 때 화면이 안 따라온다.
 *
 * <p>고르는 권한이 {@code order:update_status} 인 이유는 <b>그 화면이 하는 일이 그것</b>이라서다 —
 * 발송·배송완료·반품완료가 전부 이 하나에 걸려 있다(`V20`). 사는 사람은 안 갖는다.
 */
function canHandleOrders(me: Me): boolean {
  return me.permissions.some(
    (granted) => granted.resource === "order" && granted.action === "update_status",
  );
}

/**
 * 내 상품으로 갈 수 있나.
 *
 * <p><b>주문 권한과 따로 본다.</b> 둘이 같은 역할에 붙어 있다고 해서 한 검사로 묶으면,
 * 역할을 쪼갤 때 안 보이는 링크와 보이는 화면이 어긋난다 2014 판정의 근거는 역할이 아니라 권한이다.
 */
function canManageProducts(me: Me): boolean {
  return me.permissions.some(
    (granted) => granted.resource === "product" && granted.action === "update",
  );
}

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
 * <p><b>권한으로 가린다</b>(`13g`). 셀러 링크는 {@code order:update_status} 를 가진 사람에게만
 * 보인다 — 역할 이름을 화면에 안 박는다(`D24`).
 *
 * <p><b>묻는 곳이 한 번이다.</b> {@code /api/me} 대신 {@code /api/me/permissions} 를 부른다 —
 * 그쪽이 {@code userId} 까지 같이 주므로, 로그인 여부와 권한을 따로 물으면
 * <b>모든 화면에서 요청이 두 번</b>이 된다.
 */
export async function SiteHeader() {
  const me = await apiSessionOptional<Me>("/api/me/permissions");

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
          {/*
            셀러에게만 보인다. 사는 사람에게 그리면 **누르는 순간 튕기는 링크**가 되고,
            그건 갈 곳이 있는 것처럼 보이게 하는 것이다(`D20` 「권한 없는 것은 숨긴다」).
          */}
          {me && canHandleOrders(me) ? (
            <HeaderLink href="/seller/orders">받은 주문</HeaderLink>
          ) : null}
          {me && canManageProducts(me) ? (
            <HeaderLink href="/seller/products">내 상품</HeaderLink>
          ) : null}
          {/*
            받은 문의는 상품을 다루는 사람이 답한다(`59-1`). 판정이 `inquiry:answer` 를
            `seller_owner` 에게 `seller` 스코프로 열었고(`V54`), 그 사람이 곧 상품을 관리하는 사람이다.
          */}
          {me && canManageProducts(me) ? (
            <HeaderLink href="/seller/inquiries">받은 문의</HeaderLink>
          ) : null}
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
