import type { Metadata } from "next";
import Link from "next/link";

import { ResetConfirmForm, ResetRequestForm } from "./reset-forms";

/**
 * <b>{@code referrer} 를 안 보낸다</b>(`D14`). 이 화면의 주소에는 재설정 토큰이 실린다 — 이 화면에서 다른 곳으로
 * 나가는 요청이 {@code Referer} 에 주소를 통째로 실으면 토큰이 남의 서버 로그에 남는다.
 */
export const metadata: Metadata = {
  title: "비밀번호 재설정 · ProjectShop",
  referrer: "no-referrer",
};

/**
 * 비밀번호 재설정(`Q180`).
 *
 * <p><b>`5c-1` 이 입구 둘을 세우고 화면을 안 세웠다.</b> 메일의 링크가 이 주소를 가리키는데
 * (`app.password-reset.url-template`) 화면이 없어서, 비밀번호를 잊으면 되찾을 길이 없었다.
 *
 * <p><b>한 주소가 두 단계를 든다.</b> 토큰이 없으면 메일을 달라고 하고, 있으면 새 비밀번호를 받는다 —
 * 메일에 박힌 주소를 바꾸지 않으려는 것이다.
 */
export default async function PasswordResetPage({
  searchParams,
}: {
  searchParams: Promise<{ token?: string }>;
}) {
  const { token } = await searchParams;

  return (
    <div className="mx-auto grid w-full max-w-md flex-1 content-center gap-8 px-4 py-16">
      <div className="grid gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">비밀번호 재설정</h1>
        <p className="text-sm text-text-muted">
          {token
            ? "새로 쓰실 비밀번호를 정해 주세요."
            : "가입한 이메일로 비밀번호를 바꾸는 링크를 보내 드립니다."}
        </p>
      </div>

      {token ? <ResetConfirmForm token={token} /> : <ResetRequestForm />}

      <p className="text-sm text-text-muted">
        비밀번호가 생각나셨나요?{" "}
        <Link href="/login" className="font-semibold text-accent-text underline underline-offset-4">
          로그인
        </Link>
      </p>
    </div>
  );
}
