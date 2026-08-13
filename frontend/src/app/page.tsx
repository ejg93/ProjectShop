import Link from "next/link";

/**
 * 자리만 잡아 둔 첫 화면. 상품 목록이 생기는 청크 14 가 이걸 대신한다.
 *
 * <p>생성기가 깔아 준 안내 페이지를 지웠다 - 남겨 두면 그 마크업이 이 저장소의 관례로 읽힌다.
 */
export default function Home() {
  return (
    <main className="mx-auto grid w-full max-w-md flex-1 content-center gap-6 px-4 py-16">
      <div className="grid gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">ProjectShop</h1>
        <p className="text-sm text-text-muted">
          상품 화면은 아직 준비 중입니다. 지금은 로그인만 됩니다.
        </p>
      </div>

      <Link
        href="/login"
        className="
          justify-self-start rounded-ui bg-accent px-4 py-2.5 text-sm font-semibold text-accent-on
          transition-[background-color,transform] duration-200
          hover:bg-accent-hover
          motion-safe:active:translate-y-px
          focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
        "
      >
        로그인
      </Link>
    </main>
  );
}
