"use client";

import { ErrorScreen } from "@/components/error-screen";

import "./globals.css";

/**
 * 루트 레이아웃까지 터졌을 때 문서 전체를 대신 그린다(`Q263`).
 *
 * <p><b>머리가 못 막은 것의 마지막 울타리다.</b> 레이아웃의 머리({@code SiteHeader})가 백엔드에 누구인지 묻는데,
 * 이 파일이 없을 때 그 자리에서 던지면 모든 화면이 <b>본문 없는 500</b> 이었다 — 로그인 화면까지(`68` 실험 C).
 * 머리는 연결 실패를 스스로 삼키고, 백엔드가 답한 오류(DB 장애의 503 등)는 던져서 여기로 온다.
 *
 * <p><b>{@code html}·{@code body} 를 직접 그린다.</b> 이 파일이 뜨면 루트 레이아웃이 통째로 빠져서
 * 언어 표시도 전역 스타일도 없다 — {@code lang} 이 없으면 화면낭독기가 한글을 영어 엔진으로 읽는다(`D20`).
 * 글꼴 변수는 안 싣는다 — 시스템 글꼴로 내려앉는다.
 */
export default function GlobalError({
  error,
  retry,
}: {
  error: Error & { digest?: string };
  retry: () => void;
}) {
  return (
    <html lang="ko" className="h-full antialiased">
      <body className="min-h-full flex flex-col">
        <title>ProjectShop</title>
        <main id="main" className="flex flex-1 flex-col">
          <ErrorScreen error={error} retry={retry} />
        </main>
      </body>
    </html>
  );
}
