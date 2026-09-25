import type { Metadata } from "next";
import { GeistMono } from "geist/font/mono";
import { GeistSans } from "geist/font/sans";

import { SiteFooter } from "@/components/site-footer";
import { SiteHeader } from "@/components/site-header";

import "./globals.css";

/*
 * **글꼴 파일은 npm 패키지 안에 있다**(`Q214`). `next/font/google` 은 빌드 때 구글에서 받아서, 못 받으면
 * 빌드 전체가 죽었다(PR #77·#79 — 같은 커밋의 다른 실행은 초록). 배포도 같은 빌드라 그날은 배포가 선다.
 * `geist` 패키지는 `next/font/local` 로 자기 `.woff2` 를 가리켜 빌드가 바깥을 안 부른다.
 *
 * **한글 글꼴은 안 받는다** — Geist 에 한글 글리프가 없어 `globals.css` 의 `--font-sans` 가 시스템 글꼴
 * (`Apple SD Gothic Neo`·`Malgun Gothic`·`Noto Sans CJK KR`)로 넘긴다. 전에는 Noto Sans KR 을 구글에서 받았다.
 */

export const metadata: Metadata = {
  title: "ProjectShop",
  description: "여러 판매자가 함께 파는 쇼핑몰",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    // lang 은 화면낭독기가 어느 언어 발음으로 읽을지 고르는 근거다(`D20` 접근성).
    // en 인 채로 두면 한글을 영어 엔진이 읽으려다 뭉갠다.
    <html
      lang="ko"
      className={`${GeistSans.variable} ${GeistMono.variable} h-full antialiased`}
    >
      <body className="min-h-full flex flex-col">
        {/*
          머리의 링크를 지나 본문으로 바로 가는 링크(`D20`). 키보드만 쓰는 사람은
          화면을 옮길 때마다 같은 머리를 다시 통과한다.

          평소에는 화면 밖에 있다가 초점이 오면 나타난다. display:none 으로 숨기면
          초점 자체가 안 가서 있으나 마나다.
        */}
        <a
          href="#main"
          className="
            sr-only
            focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-10
            focus:rounded-ui focus:bg-accent focus:px-4 focus:py-2
            focus:text-sm focus:font-semibold focus:text-accent-on
          "
        >
          본문 바로가기
        </a>

        <SiteHeader />
        {/*
          `main` 표지를 셸이 한 번만 그린다(`D20` 「셸」). 화면이 각자 `main` 을 그리고
          여기서 `div` 로 감싸면 위 건너뛰기 링크는 그 `div` 에 닿고 표지는 안쪽에 남는다 —
          링크가 가리키는 곳과 보조기술이 찾는 곳이 갈린다.
        */}
        <main id="main" className="flex flex-1 flex-col">
          {children}
        </main>
        <SiteFooter />
      </body>
    </html>
  );
}
