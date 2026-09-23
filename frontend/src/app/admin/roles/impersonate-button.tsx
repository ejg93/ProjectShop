"use client";

import { useState, useTransition } from "react";

import { ApiError, api } from "@/lib/api";

/**
 * 이 사람의 화면을 본다(`16b`).
 *
 * <p><b>보기만 한다</b> — 대행 중의 쓰기는 서버 필터 한 곳이 막는다. 누르기 전에 그것을 말해 두어야
 * 관리자가 대행 중에 무엇을 고치려다 막히고 이유를 찾아 헤매지 않는다.
 *
 * <p><b>성공하면 첫 화면으로 간다.</b> 세션이 그 사람으로 바뀌어서 지금 이 관리자 화면은 그 사람에게
 * 권한이 없는 자리다 — 머물면 곧바로 거부를 본다.
 */
export function ImpersonateButton({ userId }: { userId: number }) {
  const [pending, startTransition] = useTransition();
  const [failure, setFailure] = useState<string | null>(null);

  const start = () => {
    setFailure(null);
    startTransition(async () => {
      try {
        await api("/api/admin/impersonation", { method: "POST", body: { userId } });
        window.location.assign("/");
      } catch (error) {
        setFailure(messageOf(error));
      }
    });
  };

  return (
    <div className="grid gap-2 rounded-ui border border-border p-4 text-sm">
      <p className="text-text-muted">
        이 사람이 보는 화면을 그대로 봅니다. 대행 중에는 보기만 할 수 있고, 시작과 끝이 감사 기록에 남습니다.
      </p>
      <button
        type="button"
        disabled={pending}
        onClick={start}
        className="justify-self-start rounded-ui border border-border px-4 py-2 font-medium disabled:opacity-50"
      >
        이 사람의 화면 보기
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
    case "impersonation-forbidden":
      return "이 계정은 대행할 수 없습니다. 관리자·탈퇴·정지 계정이거나 본인입니다.";
    case "impersonation-conflict":
      return "이미 다른 사용자를 대행하고 있습니다. 먼저 대행을 끝내 주세요.";
    default:
      return "대행을 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
}
