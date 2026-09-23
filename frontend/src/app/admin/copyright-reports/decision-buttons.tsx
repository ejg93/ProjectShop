"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { ApiError, api } from "@/lib/api";

/**
 * 저작권 신고를 판정한다(`Q183`).
 *
 * <p><b>서버가 받는 값은 소문자다</b>({@code taken_down}·{@code rejected}) — `Q121` 이 판정 입구를 그렇게 굳혔다.
 * 응답의 판정은 대문자라 둘이 다르다. 여기 한 곳에서만 보낸다.
 *
 * <p><b>게시 중단은 되돌릴 수 없다</b> — 사진이 저장소에서 지워진다. 그래서 한 번 더 묻는다.
 */
export function CopyrightDecisionButtons({ reportId }: { reportId: number }) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const [confirming, setConfirming] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  const decide = (decision: "taken_down" | "rejected") => {
    setFailure(null);
    startTransition(async () => {
      try {
        await api(`/api/copyright-reports/${reportId}/decision`, { method: "POST", body: { decision } });
        router.refresh();
      } catch (error) {
        setFailure(
          error instanceof ApiError && error.slug === "product-forbidden"
            ? "판정하실 권한이 없습니다."
            : "판정하지 못했습니다. 잠시 후 다시 시도해 주세요.",
        );
      }
    });
  };

  return (
    <div className="grid gap-2">
      <p role="alert" className="text-danger-text">
        {failure}
      </p>
      {confirming ? (
        <div className="flex flex-wrap items-center gap-2">
          <span>사진이 지워지고 되돌릴 수 없습니다. 게시를 중단하시겠습니까?</span>
          <button
            type="button"
            disabled={pending}
            onClick={() => decide("taken_down")}
            className="rounded-ui border border-danger-text px-3 py-1 text-xs font-medium text-danger-text disabled:opacity-50"
          >
            게시 중단
          </button>
          <button type="button" onClick={() => setConfirming(false)} className="px-3 py-1 text-xs text-text-muted">
            그만두기
          </button>
        </div>
      ) : (
        <div className="flex gap-2">
          <button
            type="button"
            onClick={() => setConfirming(true)}
            className="rounded-ui border border-danger-text px-3 py-1 text-xs font-medium text-danger-text"
          >
            게시 중단(사진 삭제)
          </button>
          <button
            type="button"
            disabled={pending}
            onClick={() => decide("rejected")}
            className="rounded-ui border border-border px-3 py-1 text-xs font-medium disabled:opacity-50"
          >
            기각
          </button>
        </div>
      )}
    </div>
  );
}
