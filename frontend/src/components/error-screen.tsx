"use client";

import Link from "next/link";
import { useEffect } from "react";

/**
 * 화면을 그리다 터졌을 때 그리는 본문(`D24` 「오류를 어느 층이 잡나」).
 *
 * <p><b>오류 경계 둘이 같이 쓴다</b>(`Q263`) — 셸 안의 {@code app/error.tsx} 와 레이아웃까지 터졌을 때의
 * {@code app/global-error.tsx}. 문구를 두 벌 두면 한쪽만 고쳐지는 날 같은 고장이 자리마다 다른 말을 한다.
 *
 * <p><b>원인을 화면에 안 적는다.</b> 서버가 준 {@code detail} 을 그대로 그리면 SQL 이나 경로가
 * 사용자에게 나갈 수 있다(`D14`). 되짚을 값은 Next 가 붙이는 오류 번호({@code digest})고 서버 로그와 짝이 맞는다(`D16`).
 *
 * <p><b>「다시 시도」는 {@code retry} 다</b> — 서버에서 다시 불러와 그린다. {@code reset} 은 오류 상태만 지우고
 * 안 불러와서, 백엔드가 살아난 뒤에 눌러도 같은 화면이 남았다(Next 16.3 이 {@code retry} 를 권한다).
 */
export function ErrorScreen({
  error,
  retry,
}: {
  error: Error & { digest?: string };
  retry: () => void;
}) {
  useEffect(() => {
    // 서버 쪽 원인은 백엔드 로그에 있고, 여기서는 브라우저 콘솔에만 남긴다.
    // 화면에 적으면 그 자체가 정보 유출이다(`D14`).
    console.error(error);
  }, [error]);

  return (
    <div className="mx-auto grid w-full max-w-xl flex-1 content-center gap-6 px-4 py-16">
      <div className="grid gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">화면을 여는 데 실패했습니다</h1>
        <p className="text-sm text-text-muted">
          잠시 후 다시 시도해 주시기 바랍니다. 계속 같은 화면이 나오면 아래 번호와 함께 알려
          주시면 원인을 찾겠습니다.
        </p>
      </div>

      {/*
        Next 가 서버 오류마다 붙이는 값이다. 서버 로그의 같은 값과 짝이 맞아서
        「어느 요청이었나」를 사람이 찾을 수 있다. 없을 수도 있어서 있을 때만 그린다.
      */}
      {error.digest ? (
        <p className="rounded-ui border border-border bg-surface-raised px-4 py-3 text-sm">
          오류 번호 <span className="font-mono">{error.digest}</span>
        </p>
      ) : null}

      <div className="flex flex-wrap gap-3">
        <button
          type="button"
          onClick={retry}
          className="
            rounded-ui bg-accent px-4 py-2.5 text-sm font-semibold text-accent-on
            transition-[background-color,transform] duration-200
            hover:bg-accent-hover
            motion-safe:active:translate-y-px
            focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
          "
        >
          다시 시도
        </button>

        <Link
          href="/products"
          className="
            rounded-ui border border-border px-4 py-2.5 text-sm
            transition-colors duration-200
            hover:border-accent-text
            focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
          "
        >
          상품 둘러보기
        </Link>
      </div>
    </div>
  );
}
