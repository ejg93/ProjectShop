"use client";

import Link from "next/link";
import { useEffect } from "react";

/**
 * 화면을 그리다 터진 것을 받는 자리(`D24` 「오류를 어느 층이 잡나」).
 *
 * <p><b>이 파일이 없으면 Next 기본 오류 화면이 뜬다</b>(`Q8`). 우리 셸도 없고 문구도 우리 것이
 * 아니고, `D20` 이 정한 존댓말도 안 걸린다. `D24` 의 표는 「5xx 는 라우트의 오류 경계」라고
 * 적어 뒀는데 그 경계가 없었다.
 *
 * <p><b>403 도 여기로 온다.</b> `apiSession` 이 401 만 잡아서 로그인으로 보내고, 403·5xx 는
 * 예외로 올라온다. `D20` 은 그 둘을 「페이지 전체」로 그리라고 하는데 지금은 404 만
 * 자기 화면이 있었다({@code not-found.tsx}).
 *
 * <p><b>원인을 화면에 안 적는다.</b> 서버가 준 {@code detail} 을 그대로 그리면 SQL 이나 경로가
 * 사용자에게 나갈 수 있다(`D14`). 되짚을 값은 추적 ID 고, 그것은 오류 응답 본문에 있다(`D16`).
 *
 * <p>{@code global-error.tsx} 는 안 만든다. 그쪽은 <b>루트 레이아웃이 터졌을 때</b>만 쓰이는데,
 * 우리 레이아웃은 데이터를 안 부르고 셸만 그린다 — 터질 자리가 없다.
 */
export default function Error({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
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
          onClick={reset}
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
