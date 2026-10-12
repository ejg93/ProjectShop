"use client";

import { ErrorScreen } from "@/components/error-screen";

/**
 * 화면을 그리다 터진 것을 받는 자리(`D24` 「오류를 어느 층이 잡나」). 본문은 {@link ErrorScreen} 이 든다.
 *
 * <p><b>이 파일이 없으면 Next 기본 오류 화면이 뜬다</b>(`Q8`). 우리 셸도 없고 문구도 우리 것이
 * 아니고, `D20` 이 정한 존댓말도 안 걸린다. `D24` 의 표는 「5xx 는 라우트의 오류 경계」라고
 * 적어 뒀는데 그 경계가 없었다.
 *
 * <p><b>403 은 여기로 안 온다</b>(`Q133`). {@code apiSession} 이 401 을 로그인으로 보내고
 * 403 은 {@code forbidden()} 으로 {@code forbidden.tsx} 를 그린다 — 고장이 아니라서
 * 「다시 시도」도 오류 번호도 없어야 하는데 이 화면은 둘 다 그린다.
 * <b>여기 남는 것은 5xx 다.</b>
 *
 * <p><b>루트 레이아웃이 터진 것은 여기로 안 온다</b> — 이 경계는 같은 층의 레이아웃을 안 감싼다.
 * 레이아웃의 머리가 백엔드를 부르므로 그 자리는 {@code global-error.tsx} 가 받는다(`Q263`).
 */
export default function Error({
  error,
  retry,
}: {
  error: Error & { digest?: string };
  retry: () => void;
}) {
  return <ErrorScreen error={error} retry={retry} />;
}
