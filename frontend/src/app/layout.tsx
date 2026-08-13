import type { Metadata } from "next";
import { Geist, Geist_Mono, Noto_Sans_KR } from "next/font/google";

import { SiteFooter } from "@/components/site-footer";
import { SiteHeader } from "@/components/site-header";

import "./globals.css";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

/**
 * 한글을 받는 글꼴. Geist 에는 한글 글리프가 없어서 이것이 없으면 시스템 기본으로 떨어진다.
 *
 * <p>굵기를 둘만 받는다. 화면이 쓰는 것이 본문과 제목뿐이라, 더 받으면 첫 화면이
 * 안 쓰는 글꼴 파일을 같이 내려받는다.
 *
 * <p>`subsets` 에 한국어가 없다. 한글 글리프가 너무 많아 구글이 부분집합을 안 내주기 때문이고,
 * 대신 브라우저가 쓰는 글자만 골라 받는다.
 */
const notoSansKr = Noto_Sans_KR({
  variable: "--font-noto-kr",
  weight: ["400", "600"],
  subsets: [],
});

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
      className={`${geistSans.variable} ${geistMono.variable} ${notoSansKr.variable} h-full antialiased`}
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
        <div id="main" className="flex flex-1 flex-col">
          {children}
        </div>
        <SiteFooter />
      </body>
    </html>
  );
}
