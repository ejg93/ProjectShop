import Link from "next/link";

/**
 * 모든 화면이 쓰는 머리. 어디에 있든 상품·장바구니·계정으로 갈 수 있다.
 *
 * <p><b>로그인 여부로 항목을 아직 안 가른다.</b> 세션 쿠키가 `HttpOnly` 라 화면이 못 읽고,
 * 무엇을 보여줄지는 권한 목록을 받아서 정하는 것이 맞다(청크 `8a`·`13b`).
 * 여기서 임시로 가르면 `13b` 가 그것을 걷어내는 일부터 하게 된다.
 *
 * <p><b>「내 정보」는 로그인 전에도 보인다</b>(`13e`). 화면만 만들고 링크를 안 걸면
 * 주소를 아는 사람만 열람·탈퇴를 할 수 있고, 그건 개인정보법 제38조제4항이 말하는
 * 「수집보다 어렵지 않게」가 아니다(`D2` R28·R24). 비로그인이 눌러도 새는 것이 없다 —
 * `/me` 는 그리기 전에 로그인으로 보낸다(`api-session.ts`).
 */
export function SiteHeader() {
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
        </nav>

        <div className="flex items-center gap-5">
          <HeaderLink href="/me">내 정보</HeaderLink>
          <HeaderLink href="/login">로그인</HeaderLink>
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
