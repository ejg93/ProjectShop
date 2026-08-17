import Link from "next/link";

/**
 * 모든 화면이 쓰는 발.
 *
 * <p><b>약관과 처리방침이 여기 있는 것은 취향이 아니다.</b> 전자상거래법이 요구하는 고지라
 * 어느 화면에서든 닿아야 한다. 가입 화면에만 두면 이미 가입한 사람은 볼 방법이 없다.
 *
 * <p><b>몰 운영자의 사업자 표시는 안 넣는다</b>(`13a-2` 에서 정했다). 전자상거래법 제10조가
 * 요구하는 값이지만 그것은 <b>이 쇼핑몰을 실제로 운영하는 사업자의 것</b>이고 이 프로젝트에는 없다.
 * 없는 상호와 등록번호를 지어 넣으면 <b>사실이 아닌 고지를 표시</b>하는 것이 되므로 비워 둔다.
 * 판매자 쪽 표시(`R1`)는 값이 있어서 상품 상세가 그린다(`14b`).
 */
export function SiteFooter() {
  return (
    <footer className="border-t border-border">
      <nav
        aria-label="약관"
        className="mx-auto flex max-w-6xl flex-wrap gap-x-5 gap-y-2 px-4 py-6 text-sm text-text-muted"
      >
        <FooterLink href="/terms">이용약관</FooterLink>
        <FooterLink href="/privacy">개인정보처리방침</FooterLink>
        {/*
          청약철회 안내도 여기 온다. 전자상거래법 제13조제2항이 청약 이전에 알리라고 해서
          주문 흐름 안에만 두면 늦다 — 사기 전에 읽을 수 있어야 한다.
        */}
        <FooterLink href="/withdrawal-guide">청약철회 안내</FooterLink>
      </nav>
    </footer>
  );
}

function FooterLink({ href, children }: { href: string; children: string }) {
  return (
    <Link
      href={href}
      className="
        rounded-ui underline underline-offset-4
        transition-colors duration-200
        hover:text-text
        focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-accent-text
      "
    >
      {children}
    </Link>
  );
}
