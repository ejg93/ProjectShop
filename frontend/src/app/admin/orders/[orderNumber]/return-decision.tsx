"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { ApiError, api } from "@/lib/api";
import { actionPath } from "@/lib/order-text";

/**
 * 반품 판정(`43a-5`, `43a-2`). 승인·거절은 관리자 몫이다 — 제17조제5항이 훼손 책임의 입증을 우리에게 지웠다(`D2` R37).
 *
 * <p><b>무엇을 그릴지는 서버가 고른다</b>({@code allowedActions}). 입고 전이면 승인이 안 실려서 거절만 선다 —
 * 판정이 입고를 요구하기 때문이다(`V63`).
 *
 * <p><b>거절 사유 한 칸이 두 곳에 간다.</b> 고객에게 알리는 사유({@code decisionReason})와 관리자가 움직인 이유
 * ({@code reason}, `D7`)가 판정에서는 같은 말이라 칸을 둘로 안 가른다.
 */
export function ReturnDecision({
  sellerOrderNumber,
  allowedActions,
}: {
  sellerOrderNumber: string;
  allowedActions: string[];
}) {
  const canApprove = allowedActions.includes("APPROVE_RETURN");
  const canReject = allowedActions.includes("REJECT_RETURN");
  if (!canApprove && !canReject) {
    return null;
  }

  return (
    <div className="grid gap-3 rounded-ui border border-border p-4 text-sm">
      <p className="font-semibold">반품 판정</p>
      {canApprove ? <ApproveForm sellerOrderNumber={sellerOrderNumber} /> : null}
      {canReject ? <RejectForm sellerOrderNumber={sellerOrderNumber} /> : null}
    </div>
  );
}

function ApproveForm({ sellerOrderNumber }: { sellerOrderNumber: string }) {
  const { pending, failure, send } = useDecision(sellerOrderNumber, "APPROVE_RETURN");

  return (
    <form
      className="grid gap-2"
      onSubmit={(event) => {
        event.preventDefault();
        const form = new FormData(event.currentTarget);
        send({ restock: form.get("restock") === "true", reason: String(form.get("reason")).trim() });
      }}
    >
      <fieldset className="grid gap-1">
        <legend>돌아온 물건을</legend>
        <label className="flex items-center gap-2">
          <input type="radio" name="restock" value="true" required defaultChecked />
          다시 판매합니다
        </label>
        <label className="flex items-center gap-2">
          <input type="radio" name="restock" value="false" />
          판매하지 않습니다
        </label>
      </fieldset>
      <label htmlFor={`approve-reason-${sellerOrderNumber}`}>승인 사유 (기록에 남습니다)</label>
      <textarea
        id={`approve-reason-${sellerOrderNumber}`}
        name="reason"
        required
        maxLength={500}
        rows={2}
        className="rounded-ui border border-border px-2 py-1"
      />
      <div>
        <button
          type="submit"
          disabled={pending}
          className="rounded-ui border border-border px-4 py-2 font-medium disabled:opacity-50"
        >
          반품 승인
        </button>
      </div>
      <p role="alert" className="text-danger-text">
        {failure}
      </p>
    </form>
  );
}

function RejectForm({ sellerOrderNumber }: { sellerOrderNumber: string }) {
  const { pending, failure, send } = useDecision(sellerOrderNumber, "REJECT_RETURN");

  return (
    <form
      className="grid gap-2"
      onSubmit={(event) => {
        event.preventDefault();
        const text = String(new FormData(event.currentTarget).get("decisionReason")).trim();
        send({ decisionReason: text, reason: text });
      }}
    >
      <label htmlFor={`reject-reason-${sellerOrderNumber}`}>거절 사유 (고객에게 알립니다)</label>
      <textarea
        id={`reject-reason-${sellerOrderNumber}`}
        name="decisionReason"
        required
        maxLength={500}
        rows={2}
        className="rounded-ui border border-border px-2 py-1"
      />
      <div>
        <button
          type="submit"
          disabled={pending}
          className="rounded-ui border border-danger-text px-4 py-2 font-medium text-danger-text disabled:opacity-50"
        >
          반품 거절
        </button>
      </div>
      <p role="alert" className="text-danger-text">
        {failure}
      </p>
    </form>
  );
}

function useDecision(sellerOrderNumber: string, action: string) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const [failure, setFailure] = useState<string | null>(null);

  const send = (body: Record<string, string | boolean>) => {
    setFailure(null);
    startTransition(async () => {
      try {
        await api(actionPath(encodeURIComponent(sellerOrderNumber), action), { method: "POST", body });
        router.refresh();
      } catch (error) {
        setFailure(
          error instanceof ApiError && error.slug === "return-not-received"
            ? "아직 입고되지 않아 승인할 수 없습니다."
            : "처리하지 못했습니다. 화면을 새로 고친 뒤 다시 시도해 주세요.",
        );
      }
    });
  };

  return { pending, failure, send };
}
