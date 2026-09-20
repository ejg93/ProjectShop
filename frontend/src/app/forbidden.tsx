import Link from "next/link";

/**
 * 로그인은 했는데 <b>그 화면을 볼 권한이 없을 때</b> 통째로 바뀌는 화면(`Q133`).
 *
 * <p><b>고장이 아니다.</b> 그전에는 이것이 {@code error.tsx} 로 떨어져서 「화면을 여는 데
 * 실패했습니다 · 잠시 후 다시 시도해 주시기 바랍니다」와 오류 번호를 그렸다 — 서버는 규칙대로
 * 답했는데 화면이 고장이라고 말했고, <b>다시 시도해도 같은 답</b>이 온다.
 *
 * <p><b>그래서 「다시 시도」가 없다.</b> 눌러서 달라지지 않는 버튼을 두면 사용자가 그것을
 * 몇 번 누르고 나서야 안 된다는 것을 안다.
 *
 * <p><b>오류 번호도 없다.</b> 되짚을 것이 없다 — 남길 사고가 아니라 정상 동작이고,
 * 판정은 백엔드 감사 기록에 이미 남는다(`D16`).
 *
 * <p><b>무엇이 막혔는지는 안 적는다.</b> 「관리자 화면입니다」라고 쓰면 그 주소에 무엇이
 * 있는지 알려 주는 셈이다(`D14`). 권한이 없다는 사실만 말한다.
 *
 * <p>이 화면을 띄우는 것은 {@code apiSession} 이다 — 서버가 403 을 주면 화면마다 잡는 대신
 * 거기서 {@code forbidden()} 을 부른다(`lib/api-session.ts`).
 */
export default function Forbidden() {
  return (
    <div className="mx-auto grid w-full max-w-xl flex-1 content-center gap-6 px-4 py-16">
      <div className="grid gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">이 화면을 보실 권한이 없습니다</h1>
        <p className="text-sm text-text-muted">
          권한이 필요한 화면입니다. 담당자에게 권한을 요청하시거나, 다른 계정으로 로그인해
          주시기 바랍니다.
        </p>
      </div>

      <div className="flex flex-wrap gap-3">
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
          상품 둘러보기
        </Link>

        <Link
          href="/login"
          className="
            rounded-ui border border-border px-4 py-2.5 text-sm
            transition-colors duration-200
            hover:border-accent-text
            focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
          "
        >
          다른 계정으로 로그인
        </Link>
      </div>
    </div>
  );
}
