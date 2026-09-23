"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { ApiError, api } from "@/lib/api";

/**
 * 접수된 신고를 받아들이거나 물린다(`Q171`).
 *
 * <p><b>한 번 누르면 되돌리지 못한다</b> — 처리한 신고는 다시 못 연다(`D7`). 잘못 받아들였으면
 * 받아들인 쪽 목록에서 후기를 되살린다.
 */
export function ReportActions({ reportId }: { reportId: number }) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const [failure, setFailure] = useState<string | null>(null);

  const resolve = (decision: "accept" | "reject") => {
    setFailure(null);
    startTransition(async () => {
      try {
        await api(`/api/review-reports/${reportId}/${decision}`, { method: "POST" });
        router.refresh();
      } catch (error) {
        setFailure(messageOf(error));
      }
    });
  };

  return (
    <div className="grid gap-2">
      <p role="alert" className="text-sm text-danger-text">
        {failure}
      </p>
      <div className="flex gap-2">
        <button
          type="button"
          disabled={pending}
          onClick={() => resolve("accept")}
          className="rounded-ui border border-danger-text px-3 py-1 text-xs font-medium text-danger-text disabled:opacity-50"
        >
          받아들이고 게시 중단
        </button>
        <button
          type="button"
          disabled={pending}
          onClick={() => resolve("reject")}
          className="rounded-ui border border-border px-3 py-1 text-xs font-medium disabled:opacity-50"
        >
          물리기
        </button>
      </div>
    </div>
  );
}

/**
 * 내린 후기를 되살린다(`Q171`). <b>이유를 적어야 누른다</b> — 이 이유가 감사에 남고(`Q167`),
 * 「누가 왜 되살렸나」에 답하는 자리가 이것뿐이다.
 */
export function RestoreForm({ reviewId }: { reviewId: number }) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const [open, setOpen] = useState(false);
  const [note, setNote] = useState("");
  const [failure, setFailure] = useState<string | null>(null);

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    setFailure(null);
    startTransition(async () => {
      try {
        await api(`/api/reviews/${reviewId}/restore`, { method: "POST", body: { note } });
        setOpen(false);
        router.refresh();
      } catch (error) {
        setFailure(messageOf(error));
      }
    });
  };

  if (!open) {
    return (
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="justify-self-start rounded-ui border border-border px-3 py-1 text-xs font-medium"
      >
        후기 되살리기
      </button>
    );
  }

  return (
    <form onSubmit={submit} className="grid gap-2">
      <label className="grid gap-1.5">
        <span className="font-medium">되살리는 이유</span>
        {/* 상한을 상수로 안 뺀다 — `ScreenLengthTest` 가 이 숫자를 서버 `@Size` 와 맞댄다 */}
        <textarea
          name="note"
          required
          maxLength={500}
          rows={2}
          value={note}
          onChange={(event) => setNote(event.target.value)}
          className="rounded-ui border border-border bg-surface px-3 py-2 text-sm"
        />
        <span className="text-text-muted">이의제기 문의 번호나 판단 근거를 적어 주세요.</span>
      </label>
      <p role="alert" className="text-sm text-danger-text">
        {failure}
      </p>
      <div className="flex gap-2">
        <button
          type="submit"
          disabled={pending}
          className="rounded-ui border border-border px-4 py-2 text-sm font-medium disabled:opacity-50"
        >
          되살리기
        </button>
        <button
          type="button"
          onClick={() => setOpen(false)}
          className="rounded-ui px-4 py-2 text-sm text-text-muted"
        >
          그만두기
        </button>
      </div>
    </form>
  );
}

/** 서버가 준 오류를 화면 문구로. <b>`slug` 로 갈린다</b>(`D5`·`D20`) */
function messageOf(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return "서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
  switch (error.slug) {
    case "review-report-already-resolved":
      return "이미 처리된 신고입니다. 화면을 새로 고쳐 주세요.";
    case "review-not-blocked":
      return "이미 게시 중인 후기입니다.";
    case "review-forbidden":
      return "신고를 처리하실 권한이 없습니다.";
    default:
      return "처리하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
}
