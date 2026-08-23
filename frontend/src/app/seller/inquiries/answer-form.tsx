"use client";

import { useRouter } from "next/navigation";
import { useRef, useState, useTransition } from "react";

import { ApiError, api } from "@/lib/api";

/**
 * 문의에 답한다(`59-1`).
 *
 * <p><b>한 번만 답한다.</b> 서버가 조건부 UPDATE 로 막고(`InquiryService.answer`)
 * 여기서는 답이 안 나간 것에만 이 폼을 그린다 — 두 겹인 이유는 앱만 있으면 새 입구가
 * 빠뜨리고, 서버만 있으면 사람이 받는 것이 예상 못 한 오류이기 때문이다(`D23` 축 2).
 */
export function AnswerForm({ inquiryNumber }: { inquiryNumber: string }) {
  const router = useRouter();
  const form = useRef<HTMLFormElement>(null);
  const [sending, setSending] = useState(false);
  const [refreshing, startRefresh] = useTransition();
  const [failure, setFailure] = useState<string | null>(null);

  const busy = sending || refreshing;

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setFailure(null);
    setSending(true);

    const data = new FormData(event.currentTarget);
    try {
      await api(`/api/inquiries/${inquiryNumber}/answer`, {
        method: "POST",
        body: { answer: String(data.get("answer")) },
      });
      form.current?.reset();
      startRefresh(() => router.refresh());
    } catch (error) {
      setFailure(error instanceof ApiError ? error.detail : "답변을 보내지 못했습니다.");
    } finally {
      setSending(false);
    }
  }

  return (
    <form ref={form} onSubmit={submit} className="grid gap-2">
      <label className="grid gap-1.5">
        <span className="font-medium">답변</span>
        <textarea
          name="answer"
          required
          maxLength={2000}
          rows={3}
          className="
            rounded-ui border border-border bg-surface px-3 py-2 text-sm
            focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent
          "
        />
        <span className="text-text-muted">
          공개 문의에 다신 답변은 다른 분께도 보입니다. 고객의 연락처를 답변에 적지 말아 주십시오.
        </span>
      </label>

      <p aria-live="polite" className="text-sm text-danger-text">
        {failure}
      </p>

      <button
        type="submit"
        disabled={busy}
        className="
          justify-self-start rounded-ui bg-accent px-4 py-2 text-sm font-semibold text-surface
          transition-colors duration-200
          hover:bg-accent-strong
          focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent
          disabled:opacity-60
        "
      >
        {busy ? "보내는 중" : "답변 등록"}
      </button>
    </form>
  );
}
