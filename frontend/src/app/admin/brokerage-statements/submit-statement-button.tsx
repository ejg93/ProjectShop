"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { ApiError, api } from "@/lib/api";

/** 서버 오류를 화면 문구로 옮긴다. `slug` 로 갈린다(`D5`) */
function messageOf(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.slug) {
      case "brokerage-statement-file-changed":
        // 그 사이 판매자 신원이 바뀌어 낸 파일과 지금 파일이 다르다. 새로 고치면 새 해시를 받는다.
        return "명세가 바뀌었습니다. 다시 내려받아 주세요.";
      case "brokerage-statement-already-submitted":
        return "이미 제출 기록이 있습니다.";
      default:
        break;
    }
  }
  return "제출 기록을 남기지 못했습니다. 화면을 새로 고쳐 주세요.";
}

/**
 * 그 분기의 제출 기록을 `POST /api/admin/brokerage-statements/{id}/submission` 으로 남긴다(`Q457`).
 *
 * <p><b>화면이 본 파일의 해시를 같이 보낸다</b> — 서버가 지금 파일과 견주어 다르면 409 다. 실패해도 새로 고친다 — 해시가 바뀌었으면
 * 새 줄이 새 해시를 든다.
 */
export function SubmitStatementButton({
  statementId,
  fileSha256,
  label,
}: {
  statementId: number;
  fileSha256: string;
  label: string;
}) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const [error, setError] = useState<string | null>(null);

  const submit = () => {
    setError(null);
    startTransition(async () => {
      try {
        await api(`/api/admin/brokerage-statements/${statementId}/submission`, {
          method: "POST",
          body: { fileSha256 },
        });
      } catch (thrown) {
        setError(messageOf(thrown));
      }
      router.refresh();
    });
  };

  return (
    <div className="grid gap-1 text-xs">
      <button type="button" disabled={pending} onClick={submit}
              className="justify-self-start rounded-ui border border-border px-2 py-0.5 disabled:opacity-50">
        제출 기록<span className="sr-only"> — {label}</span>
      </button>
      {error ? <span role="alert">{error}</span> : null}
    </div>
  );
}
