"use client";

import { useState, useTransition } from "react";

import { ApiError, api } from "@/lib/api";

/**
 * 이 사람의 2차 인증 등록을 `DELETE /api/users/{userId}/second-factor` 로 지운다(`Q462`).
 *
 * <p><b>기기를 잃은 계정을 푸는 자리다</b>(`Q461`) — 다음 로그인이 등록부터 한다. 되돌릴 수 없어서 확인 창을 먼저 띄운다(`D20`).
 * 판정은 서버가 든다 — 화면은 권한 없는 사람에게 버튼을 가릴 뿐이다.
 */
export function ResetSecondFactorButton({ userId }: { userId: number }) {
  const [pending, startTransition] = useTransition();
  const [done, setDone] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  const reset = () => {
    if (!window.confirm("이 사람의 2차 인증 등록을 지웁니다. 다음 로그인에서 인증 앱을 다시 등록해야 합니다.")) {
      return;
    }
    setDone(false);
    setFailure(null);
    startTransition(async () => {
      try {
        await api(`/api/users/${userId}/second-factor`, { method: "DELETE" });
        setDone(true);
      } catch (error) {
        setFailure(messageOf(error));
      }
    });
  };

  return (
    <div className="grid gap-2 rounded-ui border border-border p-4 text-sm">
      <p className="text-text-muted">
        인증 기기를 잃은 사람의 2차 인증을 지웁니다. 지운 기록이 감사 기록에 남습니다.
      </p>
      <button
        type="button"
        disabled={pending}
        onClick={reset}
        className="justify-self-start rounded-ui border border-border px-4 py-2 font-medium disabled:opacity-50"
      >
        2차 인증 초기화
      </button>
      <p role="status">{done ? "2차 인증을 초기화했습니다. 다음 로그인에서 다시 등록합니다." : null}</p>
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
  return error.slug === "access-denied"
    ? "2차 인증을 초기화할 권한이 없습니다."
    : "2차 인증을 초기화하지 못했습니다. 잠시 후 다시 시도해 주세요.";
}
