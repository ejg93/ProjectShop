"use client";

import { useState, useTransition } from "react";

import { ApiError, api } from "@/lib/api";
import { REVIEW_REASONS, REVIEW_REASON_TEXT, type ReviewReason } from "@/lib/review-text";

/**
 * 후기를 신고한다(`Q171`).
 *
 * <p><b>사유는 공개한 운영정책의 삭제 기준 넷 중에서만 고른다</b>(`D2` `R27`). 자유 글을 받으면
 * 관리자가 「이것이 넷 중 무엇인가」를 다시 판단해야 하고, 그 판단이 쓴 사람에게 알리는 사유와 갈린다.
 *
 * <p><b>로그인한 사람에게만 그린다</b> — 부르는 쪽이 권한 목록으로 가른다. 비로그인에게 그리면
 * 누르는 순간 로그인으로 튕기는 버튼이 된다(`D20` 「권한 없는 것은 숨긴다」).
 */
export function ReportButton({ reviewId }: { reviewId: number }) {
  const [pending, startTransition] = useTransition();
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState<ReviewReason>("ADVERTISEMENT");
  const [message, setMessage] = useState<string | null>(null);

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    setMessage(null);
    startTransition(async () => {
      try {
        await api(`/api/reviews/${reviewId}/report`, { method: "POST", body: { reason } });
        setOpen(false);
        setMessage("신고가 접수되었습니다. 관리자가 확인합니다.");
      } catch (error) {
        setMessage(messageOf(error));
      }
    });
  };

  return (
    <div className="grid gap-2 text-xs">
      <p role="status" className="text-text-muted">
        {message}
      </p>
      {open ? (
        <form onSubmit={submit} className="flex flex-wrap items-end gap-2">
          <label className="grid gap-1">
            <span className="text-text-muted">신고 사유</span>
            <select
              name="reason"
              value={reason}
              onChange={(event) => setReason(event.target.value as ReviewReason)}
              className="rounded-ui border border-border bg-surface px-2 py-1"
            >
              {REVIEW_REASONS.map((value) => (
                <option key={value} value={value}>
                  {REVIEW_REASON_TEXT[value]}
                </option>
              ))}
            </select>
          </label>
          <button
            type="submit"
            disabled={pending}
            className="rounded-ui border border-border px-3 py-1 font-medium disabled:opacity-50"
          >
            신고하기
          </button>
          <button
            type="button"
            onClick={() => setOpen(false)}
            className="rounded-ui px-3 py-1 text-text-muted"
          >
            그만두기
          </button>
        </form>
      ) : (
        <button
          type="button"
          onClick={() => setOpen(true)}
          className="justify-self-start text-text-muted underline underline-offset-4"
        >
          신고
        </button>
      )}
    </div>
  );
}

/** 서버가 준 오류를 화면 문구로. <b>`slug` 로 갈린다</b>(`D5`·`D20`) */
function messageOf(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return "서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
  switch (error.slug) {
    case "review-already-reported":
      return "이미 신고하신 후기입니다.";
    case "review-forbidden":
      return "직접 쓰신 후기는 신고하실 수 없습니다. 내 후기에서 지우실 수 있습니다.";
    case "review-not-found":
      return "이미 내려갔거나 지워진 후기입니다.";
    default:
      return "신고하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
}
