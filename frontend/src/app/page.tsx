import Link from "next/link";

/**
 * 홈. 지금은 들어온 사람을 상품 목록으로 보낸다(`D20` 화면 지도).
 *
 * <p>청크 14 가 여기를 채운다. 자동으로 넘기지 않는 이유는 <b>뒤로 가기가 막히기 때문</b>이다 -
 * 넘겨 두면 상품 목록에서 뒤로 갈 때마다 다시 목록으로 튕긴다.
 */
export default function Home() {
  return (
    <div className="mx-auto grid w-full max-w-6xl flex-1 content-center gap-6 px-4 py-16">
      <div className="grid gap-2">
        <h1 className="text-3xl font-semibold tracking-tight">
          여러 판매자가 함께 파는 곳
        </h1>
        <p className="text-sm text-text-muted">
          한 번 주문하면 판매자별로 나뉘어 배송됩니다.
        </p>
      </div>

      <Link
        href="/products"
        className="
          justify-self-start rounded-ui bg-accent px-4 py-2.5 text-sm font-semibold text-accent-on
          transition-[background-color,transform] duration-200
          hover:bg-accent-hover
          motion-safe:active:translate-y-px
          focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
        "
      >
        상품 보기
      </Link>
    </div>
  );
}
