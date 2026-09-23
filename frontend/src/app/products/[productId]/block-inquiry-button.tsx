"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { ApiError, api } from "@/lib/api";

/**
 * 공개 문의의 게시를 중단한다(`Q184`, `D2` `R34`).
 *
 * <p><b>`59-2` 가 입구를 세우고 화면을 안 세워서</b> 관리자가 광고성 문의를 내리려면 `psql` 이었다.
 * 정보통신망법 제50조의7 의 의무자는 운영자라 관리자만 그린다 — 부르는 쪽이 권한 목록으로 가른다.
 *
 * <p><b>사유를 값으로 가른다</b> — 광고는 법이 근거고(제50조의7) 욕설은 약관이 근거다. 섞이면 개정될 때 무엇을
 * 고쳐야 하는지 모른다({@code BlockReason}).
 */
export function BlockInquiryButton({ inquiryNumber }: { inquiryNumber: string }) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const [open, setOpen] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  const block = (reason: "ADVERTISEMENT" | "ABUSE") => {
    setFailure(null);
    startTransition(async () => {
      try {
        await api(`/api/inquiries/${inquiryNumber}/block`, { method: "POST", body: { reason } });
        router.refresh();
      } catch (error) {
        setFailure(
          error instanceof ApiError && error.slug === "inquiry-already-closed"
            ? "이미 내려간 문의입니다."
            : "게시를 중단하지 못했습니다. 잠시 후 다시 시도해 주세요.",
        );
      }
    });
  };

  return (
    <div className="grid gap-1 text-xs">
      {open ? (
        <div className="flex flex-wrap items-center gap-2">
          <span>어떤 사유로 내리시겠습니까?</span>
          <button type="button" disabled={pending} onClick={() => block("ADVERTISEMENT")}
                  className="rounded-ui border border-danger-text px-2 py-0.5 text-danger-text disabled:opacity-50">
            광고성 정보
          </button>
          <button type="button" disabled={pending} onClick={() => block("ABUSE")}
                  className="rounded-ui border border-danger-text px-2 py-0.5 text-danger-text disabled:opacity-50">
            욕설·비방
          </button>
          <button type="button" onClick={() => setOpen(false)} className="px-2 py-0.5 text-text-muted">
            그만두기
          </button>
        </div>
      ) : (
        <button type="button" onClick={() => setOpen(true)} className="justify-self-start text-text-muted underline">
          게시 중단
        </button>
      )}
      <p role="alert" className="text-danger-text">
        {failure}
      </p>
    </div>
  );
}
