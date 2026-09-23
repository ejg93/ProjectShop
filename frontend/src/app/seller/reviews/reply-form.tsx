"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { ApiError, api } from "@/lib/api";

/**
 * 후기에 답한다(`Q171`).
 *
 * <p><b>후기 하나에 답글 하나다</b>(`48`). 그래서 「달기」와 「고치기」가 같은 입구({@code PUT})고,
 * 이미 단 답글이 있으면 그 글을 채운 채로 연다.
 */
export function ReplyForm({ reviewId, reply }: { reviewId: number; reply: string | null }) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const [open, setOpen] = useState(false);
  const [body, setBody] = useState(reply ?? "");
  const [failure, setFailure] = useState<string | null>(null);

  const run = (request: () => Promise<unknown>) => {
    setFailure(null);
    startTransition(async () => {
      try {
        await request();
        setOpen(false);
        router.refresh();
      } catch (error) {
        setFailure(messageOf(error));
      }
    });
  };

  const save = (event: React.FormEvent) => {
    event.preventDefault();
    run(() => api(`/api/reviews/${reviewId}/reply`, { method: "PUT", body: { body } }));
  };

  return (
    <div className="grid gap-2">
      {reply !== null && !open ? (
        <div className="grid gap-1 rounded-ui bg-surface p-4">
          <p className="text-text-muted">내 답글</p>
          <p className="whitespace-pre-wrap">{reply}</p>
        </div>
      ) : null}

      <p role="alert" className="text-sm text-danger-text">
        {failure}
      </p>

      {open ? (
        <form onSubmit={save} className="grid gap-2">
          <label className="grid gap-1.5">
            <span className="font-medium">답글</span>
            {/* 상한을 상수로 안 뺀다 — `ScreenLengthTest` 가 이 숫자를 서버 `@Size` 와 맞댄다 */}
            <textarea
              name="body"
              required
              maxLength={2000}
              rows={3}
              value={body}
              onChange={(event) => setBody(event.target.value)}
              className="
                rounded-ui border border-border bg-surface px-3 py-2 text-sm
                focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent
              "
            />
            <span className="text-text-muted">
              답글은 누구나 봅니다. 고객의 연락처나 주문 내용을 적지 말아 주세요.
            </span>
          </label>
          <div className="flex gap-2">
            <button
              type="submit"
              disabled={pending}
              className="rounded-ui bg-accent px-4 py-2 text-sm font-semibold text-surface disabled:opacity-60"
            >
              {reply === null ? "답글 등록" : "고친 답글 저장"}
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
      ) : (
        <div className="flex gap-2">
          <button
            type="button"
            onClick={() => setOpen(true)}
            className="rounded-ui border border-border px-3 py-1 text-xs font-medium"
          >
            {reply === null ? "답글 달기" : "답글 고치기"}
          </button>
          {reply === null ? null : (
            <button
              type="button"
              disabled={pending}
              onClick={() => run(() => api(`/api/reviews/${reviewId}/reply`, { method: "DELETE" }))}
              className="rounded-ui border border-border px-3 py-1 text-xs font-medium disabled:opacity-50"
            >
              답글 지우기
            </button>
          )}
        </div>
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
    case "review-forbidden":
      return "이 후기에 답하실 권한이 없습니다.";
    case "review-not-found":
      return "후기가 내려갔거나 지워졌습니다. 화면을 새로 고쳐 주세요.";
    default:
      return "답글을 저장하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
}
