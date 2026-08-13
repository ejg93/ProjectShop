import type { Metadata } from "next";
import Link from "next/link";

import { LoginForm } from "./login-form";

export const metadata: Metadata = {
  title: "로그인 · ProjectShop",
};

/**
 * 로그인 화면.
 *
 * <p>서버 컴포넌트다. 움직이는 것은 {@link LoginForm} 하나뿐이라 그것만 클라이언트로 뗐다.
 *
 * <p><b>가운데 정렬을 골랐다.</b> 레이아웃 다이얼이 4 라 비대칭을 안 쓰는 구간이고,
 * 이 화면에는 읽을 것이 하나뿐이라 시선을 나눌 이유가 없다.
 *
 * <p>큰 사진을 안 넣는다. 랜딩이 아니라 <b>지나가는 화면</b>이라, 첫 그림이 커질수록
 * 할 일(입력)에 닿는 시간만 늘어난다.
 */
export default function LoginPage() {
  return (
    <main className="mx-auto grid w-full max-w-md flex-1 content-center gap-8 px-4 py-16">
      <div className="grid gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">로그인</h1>
        <p className="text-sm text-text-muted">
          주문 내역과 장바구니를 계정에 묶어 둡니다.
        </p>
      </div>

      <LoginForm />

      {/*
        가입 화면은 아직 자리표시지만 링크는 건다(`D20`). 지도에 있는 경로라
        `13d` 가 자리표시를 지우면 이 링크는 그대로 살아 있다.
      */}
      <p className="text-sm text-text-muted">
        아직 계정이 없으신가요?{" "}
        <Link
          href="/signup"
          className="font-semibold text-accent-text underline underline-offset-4"
        >
          회원가입
        </Link>
      </p>
    </main>
  );
}
