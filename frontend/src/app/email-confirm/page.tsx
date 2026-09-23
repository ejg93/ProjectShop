import type { Metadata } from "next";
import Link from "next/link";

import { apiSessionOptional } from "@/lib/api-session";
import type { Me } from "@/lib/permissions";

import { EmailConfirmButton } from "./confirm-button";

/** 주소에 확인 토큰이 실린다 — {@code Referer} 로 남의 로그에 새지 않게 한다(`D14`, `Q180` 과 같다) */
export const metadata: Metadata = {
  title: "이메일 주소 확인 · ProjectShop",
  referrer: "no-referrer",
};

/**
 * 이메일 변경 확인(`Q181`).
 *
 * <p><b>`5e-1` 이 입구와 메일을 세우고 화면을 안 세웠다.</b> 확인 메일의 링크가 이 주소를 가리키는데
 * (`app.email-change.url-template`) 화면이 없어서, 이메일을 바꾸려는 사람이 확정할 길이 없었다.
 *
 * <p><b>로그인한 사람만 확정한다</b> — 서버가 그렇게 막는다(링크를 주운 사람이 남의 계정을 못 바꾸게).
 * 로그인 안 한 채로 열면 로그인한 뒤 <b>이 링크를 다시 열라고</b> 알린다 — 로그인 화면으로 곧장 보내면
 * 돌아올 주소(토큰)를 잃는다.
 */
export default async function EmailConfirmPage({
  searchParams,
}: {
  searchParams: Promise<{ token?: string }>;
}) {
  const { token } = await searchParams;
  const me = await apiSessionOptional<Me>("/api/me/permissions");

  return (
    <div className="mx-auto grid w-full max-w-md flex-1 content-center gap-8 px-4 py-16">
      <div className="grid gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">이메일 주소 확인</h1>
        <p className="text-sm text-text-muted">
          내 정보에서 요청하신 새 이메일 주소를 확정합니다.
        </p>
      </div>

      {!token ? (
        <p role="alert" className="text-sm">
          링크가 올바르지 않습니다. 메일의 링크를 그대로 열어 주세요.
        </p>
      ) : me === null ? (
        <p role="status" className="rounded-ui border border-border bg-surface-raised px-4 py-3 text-sm">
          확정은 로그인한 뒤에 할 수 있습니다.{" "}
          <Link href="/login" className="font-semibold underline underline-offset-4">
            로그인
          </Link>
          하신 다음 메일의 링크를 다시 열어 주세요.
        </p>
      ) : (
        <EmailConfirmButton token={token} />
      )}
    </div>
  );
}
