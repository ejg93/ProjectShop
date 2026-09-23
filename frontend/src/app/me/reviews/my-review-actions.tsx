"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { ApiError, api } from "@/lib/api";

/**
 * 내 후기를 고치고 지운다(`Q171`).
 *
 * <p><b>별점과 글을 같이 고친다</b> — 서버가 둘을 같이 받는다(`Q160`). 글만 고치면 「별은 그대로인데
 * 말이 달라진」 후기가 남는다.
 *
 * <p><b>지우기는 한 번 더 묻는다.</b> 대화상자가 아니라 이 자리에서 — 되돌릴 수 없는 것을 한 번에
 * 누르게 두면 목록을 훑다가 잘못 누른 손이 그대로 남는다. 지운 뒤에는 같은 주문에 다시 쓸 수 있다.
 */
export function MyReviewActions({
  reviewId,
  rating: initialRating,
  body: initialBody,
}: {
  reviewId: number;
  rating: number;
  body: string;
}) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const [mode, setMode] = useState<"idle" | "editing" | "confirmingDelete">("idle");
  const [rating, setRating] = useState(initialRating);
  const [body, setBody] = useState(initialBody);
  const [failure, setFailure] = useState<string | null>(null);

  const run = (request: () => Promise<unknown>) => {
    setFailure(null);
    startTransition(async () => {
      try {
        await request();
        setMode("idle");
        router.refresh();
      } catch (error) {
        setFailure(messageOf(error));
      }
    });
  };

  const save = (event: React.FormEvent) => {
    event.preventDefault();
    run(() => api(`/api/reviews/${reviewId}`, { method: "PATCH", body: { rating, body } }));
  };

  return (
    <div className="grid gap-2">
      <p role="alert" className="text-sm text-danger-text">
        {failure}
      </p>

      {mode === "editing" ? (
        <form onSubmit={save} className="grid gap-3">
          <div className="grid gap-1">
            <label className="text-xs text-text-muted" htmlFor={`rating-${reviewId}`}>
              별점
            </label>
            <select
              id={`rating-${reviewId}`}
              name="rating"
              value={rating}
              onChange={(event) => setRating(Number(event.target.value))}
              className="w-32 rounded-ui border border-border bg-surface px-3 py-2 text-sm"
            >
              {[5, 4, 3, 2, 1].map((value) => (
                <option key={value} value={value}>
                  {"★".repeat(value)}
                </option>
              ))}
            </select>
          </div>
          <div className="grid gap-1">
            <label className="text-xs text-text-muted" htmlFor={`body-${reviewId}`}>
              고칠 내용 (10자 이상)
            </label>
            {/* 상한을 상수로 안 뺀다 — `ScreenLengthTest` 가 이 숫자를 서버 `@Size` 와 맞댄다 */}
            <textarea
              id={`body-${reviewId}`}
              name="body"
              required
              minLength={10}
              maxLength={2000}
              rows={4}
              value={body}
              onChange={(event) => setBody(event.target.value)}
              className="rounded-ui border border-border bg-surface px-3 py-2 text-sm"
            />
          </div>
          <div className="flex gap-2">
            <button
              type="submit"
              disabled={pending}
              className="rounded-ui border border-border px-4 py-2 text-sm font-medium disabled:opacity-50"
            >
              고친 내용 저장
            </button>
            <button
              type="button"
              onClick={() => setMode("idle")}
              className="rounded-ui px-4 py-2 text-sm text-text-muted"
            >
              그만두기
            </button>
          </div>
        </form>
      ) : mode === "confirmingDelete" ? (
        <div className="flex flex-wrap items-center gap-2">
          <span>이 후기를 지우시겠습니까?</span>
          <button
            type="button"
            disabled={pending}
            onClick={() => run(() => api(`/api/reviews/${reviewId}`, { method: "DELETE" }))}
            className="rounded-ui border border-danger-text px-3 py-1 text-xs font-medium text-danger-text disabled:opacity-50"
          >
            지우기
          </button>
          <button
            type="button"
            onClick={() => setMode("idle")}
            className="rounded-ui px-3 py-1 text-xs text-text-muted"
          >
            그만두기
          </button>
        </div>
      ) : (
        <div className="flex gap-2">
          <button
            type="button"
            onClick={() => setMode("editing")}
            className="rounded-ui border border-border px-3 py-1 text-xs font-medium"
          >
            고치기
          </button>
          <button
            type="button"
            onClick={() => setMode("confirmingDelete")}
            className="rounded-ui border border-border px-3 py-1 text-xs font-medium"
          >
            지우기
          </button>
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
    case "review-not-found":
      return "이미 지워진 후기입니다. 화면을 새로 고쳐 주세요.";
    case "validation-failed":
      return "후기는 10자 이상 2000자 이하로 써 주세요.";
    default:
      return "처리하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
}
