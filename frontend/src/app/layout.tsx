import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import "./globals.css";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
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
      className={`${geistSans.variable} ${geistMono.variable} h-full antialiased`}
    >
      <body className="min-h-full flex flex-col">{children}</body>
    </html>
  );
}
