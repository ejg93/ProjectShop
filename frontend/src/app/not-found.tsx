import Link from "next/link";

/**
 * 없는 주소. <b>`notFound()` 를 부른 라우트와 지도에 없는 주소가 둘 다 여기로 온다.</b>
 *
 * <p>Next 가 기본 화면을 들고 있지만 영문이라 우리 문구 규칙(`D20` 「화면 문구는 존댓말이다」)과
 * 갈린다. 라우트마다 만들지 않고 뿌리에 하나 둔다 — 없는 주소에 하는 말이 화면마다 다를 이유가 없다.
 *
 * <p><b>무엇이 없는지는 안 말한다.</b> 「없는 상품」과 「아직 안 파는 상품」을 가르면
 * 주소를 하나씩 두드려서 남의 `draft` 가 존재한다는 것을 알아낼 수 있다(`8b` 가 서버에서 정한 것).
 */
export default function NotFound() {
  return (
    <div className="mx-auto grid w-full max-w-6xl flex-1 content-center justify-items-start gap-3 px-4 py-16">
      <h1 className="text-2xl font-semibold tracking-tight">찾으시는 쪽이 없습니다</h1>
      <p className="text-sm text-text-muted">
        주소가 바뀌었거나 더 이상 열려 있지 않은 쪽입니다.
      </p>
      <Link
        href="/products"
        className="
          rounded-ui bg-accent px-4 py-2.5 text-sm font-semibold text-accent-on
          transition-[background-color,transform] duration-200
          hover:bg-accent-hover
          motion-safe:active:translate-y-px
          focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
        "
      >
        상품 보러 가기
      </Link>
    </div>
  );
}
