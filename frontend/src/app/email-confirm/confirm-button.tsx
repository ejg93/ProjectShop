"use client";

import Link from "next/link";
import { useState, useTransition } from "react";

import { ApiError, api } from "@/lib/api";

/**
 * 이메일 변경을 확정한다(`Q181`).
 *
 * <p><b>누르게 한다 — 열자마자 보내지 않는다.</b> 메일 보안 검사기가 링크를 미리 열어 보는 일이 흔해서,
 * 화면이 열리는 것만으로 확정하면 <b>사람이 누르기 전에</b> 주소가 바뀐다.
 */
export function EmailConfirmButton({ token }: { token: string }) {
  const [pending, startTransition] = useTransition();
  const [done, setDone] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  const confirm = () => {
    setFailure(null);
    startTransition(async () => {
      try {
        await api("/api/me/email/confirm", { method: "POST", body: { token } });
        setDone(true);
      } catch (error) {
        setFailure(messageOf(error));
      }
    });
  };

  if (done) {
    return (
      <p role="status" className="rounded-ui border border-border bg-surface-raised px-4 py-3 text-sm">
        이메일 주소를 바꿨습니다. 다음 로그인부터 새 주소를 쓰시면 됩니다.{" "}
        <Link href="/me" className="font-semibold underline underline-offset-4">
          내 정보로 가기
        </Link>
      </p>
    );
  }

  return (
    <div className="grid gap-3">
      <button
        type="button"
        disabled={pending}
        onClick={confirm}
        className="justify-self-start rounded-ui bg-accent px-4 py-2 text-sm font-semibold text-surface disabled:opacity-60"
      >
        {pending ? "확인하는 중" : "새 이메일 주소로 바꾸기"}
      </button>
      <p role="alert" className="text-sm text-danger-text">
        {failure}
      </p>
    </div>
  );
}

/** 서버가 준 오류를 화면 문구로. <b>`slug` 로 갈린다</b>(`D5`·`D20`) */
function messageOf(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return "서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
  switch (error.slug) {
    case "email-change-token-invalid":
      return "쓸 수 없는 링크입니다. 이미 썼거나 시간이 지났을 수 있습니다. 내 정보에서 다시 요청해 주세요.";
    case "email-taken":
      return "그사이 다른 계정이 그 주소를 쓰게 되었습니다. 다른 주소로 다시 요청해 주세요.";
    default:
      return "바꾸지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
}
