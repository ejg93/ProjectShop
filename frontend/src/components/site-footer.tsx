import Link from "next/link";

/**
 * 모든 화면이 쓰는 발.
 *
 * <p><b>약관과 처리방침이 여기 있는 것은 취향이 아니다.</b> 전자상거래법이 요구하는 고지라
 * 어느 화면에서든 닿아야 한다. 가입 화면에만 두면 이미 가입한 사람은 볼 방법이 없다.
 *
 * <p>사업자 표시(상호·대표자·사업자등록번호·통신판매업 신고번호)도 같은 이유로 여기 온다.
 * 지금은 넣을 값이 없다 — 우리 것이 아니라 <b>이 쇼핑몰을 운영하는 사업자의 값</b>이고,
 * 그것을 담을 자리가 아직 없다(청크 `13a` 가 문안을 쓰면서 같이 본다).
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
