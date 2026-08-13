import type { Metadata } from "next";
import { Geist, Geist_Mono, Noto_Sans_KR } from "next/font/google";
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
      <body className="min-h-full flex flex-col">{children}</body>
    </html>
  );
}
