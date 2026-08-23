"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { ApiError, api } from "@/lib/api";

/**
 * 낸 문의를 거둔다(`59-1`).
 *
 * <p><b>`58` 이 상태 목록에 {@code withdrawn} 을 넣고 `59` 가 옮기는 코드를 안 만들었다</b> —
 * 값이 `check` 에 있으면 다음 사람이 그 상태가 도달 가능하다고 읽는다(1차 마무리가 잡았다).
 * 그 버튼이 여기다.
 *
 * <p><b>확인을 안 받는다.</b> 되돌릴 수는 없지만 잃는 것이 자기 질문 하나뿐이고,
 * 같은 내용을 다시 낼 수 있다 — 탈퇴(`/me/withdraw`)가 자기 화면을 갖는 것과 갈리는 자리다.
 */
export function WithdrawButton({ inquiryNumber }: { inquiryNumber: string }) {
  const router = useRouter();
  const [sending, setSending] = useState(false);
  const [refreshing, startRefresh] = useTransition();
  const [failure, setFailure] = useState<string | null>(null);

  const busy = sending || refreshing;

  async function withdraw() {
    setFailure(null);
    setSending(true);
    try {
      await api(`/api/inquiries/${inquiryNumber}/withdrawal`, { method: "POST" });
      startRefresh(() => router.refresh());
    } catch (error) {
      setFailure(error instanceof ApiError ? error.detail : "거두지 못했습니다.");
    } finally {
      setSending(false);
    }
  }

  return (
    <div className="grid gap-1.5 justify-items-start">
      <button
        type="button"
        onClick={withdraw}
        disabled={busy}
        className="
          rounded-ui border border-border px-3 py-2 text-sm font-medium
          transition-colors duration-200
          hover:bg-surface
          focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent
          disabled:opacity-60
        "
      >
        {busy ? "거두는 중" : "문의 거두기"}
      </button>

      <p aria-live="polite" className="text-sm text-danger-text">
        {failure}
      </p>
    </div>
  );
}
