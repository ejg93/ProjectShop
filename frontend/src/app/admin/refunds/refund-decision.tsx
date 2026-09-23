"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { ApiError, api } from "@/lib/api";
import { priceText } from "@/lib/format";

/**
 * 환불을 승인하거나 반려한다(`Q185`).
 *
 * <p><b>버튼은 서버가 고른다</b>({@code allowedActions}) — 권한만 보고 그리면 자기가 낸 요청에도 버튼이 난다.
 * 모르는 이름은 무시한다(`D5`).
 *
 * <p><b>승인은 되돌릴 수 없어서 한 번 묻는다</b> — 누르는 순간 결제 대행사로 돈이 나간다. 묻는 말에 금액을 적어서
 * 무엇을 승인하는지 누르기 전에 보이게 한다. <b>반려는 사유가 있어야 보낸다</b> — 고객에게 왜 안 됐는지 답하는
 * 값이라 서버와 제약도 비어 있는 것을 막는다({@code refund_rejection_reason_check}).
 */
export function RefundDecision({
  refundNumber,
  amount,
  overdue,
  allowedActions,
}: {
  refundNumber: string;
  amount: number;
  /** 기한을 넘겼나. 넘겼으면 승인 때 서버가 지연배상금을 더해 보낸다({@code RefundMath.delayInterest}) */
  overdue: boolean;
  allowedActions: string[];
}) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const [mode, setMode] = useState<"idle" | "approve" | "reject">("idle");
  const [reason, setReason] = useState("");
  const [failure, setFailure] = useState<string | null>(null);

  const canApprove = allowedActions.includes("APPROVE");
  const canReject = allowedActions.includes("REJECT");
  if (!canApprove && !canReject) {
    return null;
  }

  const decide = (action: "approve" | "reject") => {
    setFailure(null);
    startTransition(async () => {
      try {
        await api(`/api/refunds/${encodeURIComponent(refundNumber)}/${action}`, {
          method: "POST",
          body: action === "reject" ? { reason: reason.trim() } : undefined,
        });
        router.refresh();
      } catch (error) {
        setFailure(failureText(error));
      }
    });
  };

  return (
    <div className="grid gap-2 text-sm">
      {mode === "idle" ? (
        <div className="flex gap-2">
          {canApprove ? (
            <button type="button" onClick={() => setMode("approve")}
                    className="rounded-ui border border-accent px-3 py-1">
              승인
            </button>
          ) : null}
          {canReject ? (
            <button type="button" onClick={() => setMode("reject")}
                    className="rounded-ui border border-border px-3 py-1">
              반려
            </button>
          ) : null}
        </div>
      ) : null}

      {mode === "approve" ? (
        <div className="grid gap-1">
          <span>
            {overdue
              ? `환급 기한을 넘겨 고객에게 ${priceText(amount)}에 지연배상금을 더해 돌려줍니다. 승인하시겠습니까?`
              : `고객에게 ${priceText(amount)}을 돌려줍니다. 승인하시겠습니까?`}
          </span>
          <div className="flex gap-2">
            <button type="button" disabled={pending} onClick={() => decide("approve")}
                    className="rounded-ui border border-accent px-3 py-1 disabled:opacity-50">
              승인
            </button>
            <button type="button" onClick={() => setMode("idle")} className="px-3 py-1 text-text-muted">
              그만두기
            </button>
          </div>
        </div>
      ) : null}

      {mode === "reject" ? (
        <form
          className="grid gap-1"
          onSubmit={(event) => {
            event.preventDefault();
            decide("reject");
          }}
        >
          <label htmlFor={`reject-${refundNumber}`}>반려 사유</label>
          <textarea
            id={`reject-${refundNumber}`}
            name="reason"
            required
            maxLength={500}
            rows={2}
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            aria-describedby={`reject-${refundNumber}-hint`}
            className="rounded-ui border border-border px-2 py-1"
          />
          <span id={`reject-${refundNumber}-hint`} className="text-xs text-text-muted">
            고객에게 그대로 전달됩니다.
          </span>
          <div className="flex gap-2">
            <button type="submit" disabled={pending || reason.trim() === ""}
                    className="rounded-ui border border-danger-text px-3 py-1 text-danger-text disabled:opacity-50">
              반려
            </button>
            <button type="button" onClick={() => setMode("idle")} className="px-3 py-1 text-text-muted">
              그만두기
            </button>
          </div>
        </form>
      ) : null}

      <p role="alert" className="text-danger-text">
        {failure}
      </p>
    </div>
  );
}

function failureText(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.slug) {
      case "refund-already-decided":
        return "이미 처리된 환불입니다. 화면을 새로 고쳐 주세요.";
      case "refund-self-approval":
        return "직접 낸 요청은 다른 관리자가 처리해야 합니다.";
      case "payment-gateway-unavailable":
        return "결제 대행사가 응답하지 않습니다. 잠시 후 다시 시도해 주세요.";
    }
  }
  return "처리하지 못했습니다. 잠시 후 다시 시도해 주세요.";
}
