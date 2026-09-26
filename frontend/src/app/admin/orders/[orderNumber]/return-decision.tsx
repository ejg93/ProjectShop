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
 *
 * <p><b>거절 사유의 종류는 닫힌 목록이다</b>(`Q212`). 「상품 훼손」은 검수 소견이 있어야 고를 수 있다 — 입증책임이 우리에게
 * 있어서다(제17조제5항). **고를 수 있는 사유는 서버가 싣는다**({@code rejectionReasons}, `Q234`·`Q235`) — 화면이
 * 검수 시각을 보고 다시 판단하면 규칙이 세 벌(제약·서비스·화면)이 된다. 라벨만 화면이 든다.
 */
export function ReturnDecision({
  sellerOrderNumber,
  allowedActions,
  rejectionReasons,
}: {
  sellerOrderNumber: string;
  allowedActions: string[];
  /** 거절할 때 고를 수 있는 사유(대문자). 검수 전이면 `DAMAGED` 가 없다 */
  rejectionReasons: string[];
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
      {canReject ? <RejectForm sellerOrderNumber={sellerOrderNumber} reasons={rejectionReasons} /> : null}
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

/** 거절 사유의 라벨(`Q212`). 값은 서버 열거값 그대로고 **무엇을 그릴지는 서버 목록이 정한다**(`Q235`). 모르는 값은 건너뛴다(`D5`) */
const REJECTION_REASON_TEXT: Record<string, string> = {
  DAMAGED: "상품 훼손",
  PERIOD_EXPIRED: "청약철회 기간 경과",
  RESTRICTED: "청약철회 제한 사유",
  OTHER: "그 밖의 사유",
};

function RejectForm({ sellerOrderNumber, reasons }: { sellerOrderNumber: string; reasons: string[] }) {
  const damagedOpen = reasons.includes("DAMAGED");
  const { pending, failure, send } = useDecision(sellerOrderNumber, "REJECT_RETURN");

  return (
    <form
      className="grid gap-2"
      onSubmit={(event) => {
        event.preventDefault();
        const form = new FormData(event.currentTarget);
        const text = String(form.get("decisionReason")).trim();
        send({ reasonCode: String(form.get("reasonCode")), decisionReason: text, reason: text });
      }}
    >
      <label htmlFor={`reject-code-${sellerOrderNumber}`}>거절 사유 종류</label>
      <select
        id={`reject-code-${sellerOrderNumber}`}
        name="reasonCode"
        required
        defaultValue=""
        aria-describedby={damagedOpen ? undefined : `reject-code-hint-${sellerOrderNumber}`}
        className="rounded-ui border border-border px-2 py-1"
      >
        <option value="" disabled>
          골라 주세요
        </option>
        {reasons
          .filter((reason) => reason in REJECTION_REASON_TEXT)
          .map((reason) => (
            <option key={reason} value={reason}>
              {REJECTION_REASON_TEXT[reason]}
            </option>
          ))}
      </select>
      {damagedOpen ? null : (
        <p id={`reject-code-hint-${sellerOrderNumber}`} className="text-text-muted">
          상품 훼손은 검수 소견이 있어야 고를 수 있습니다.
        </p>
      )}
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
        setFailure(failureText(error));
      }
    });
  };

  return { pending, failure, send };
}

/** 오류 이름으로 자기 문구를 고른다 — 이름마다 안내가 달라서다(`D20` 「서버 문구는 `message` 만 그린다」 — 할 일이 다르면 슬러그로 가른다) */
function failureText(error: unknown): string {
  if (error instanceof ApiError && error.slug === "return-not-received") {
    return "아직 입고되지 않아 승인할 수 없습니다.";
  }
  if (error instanceof ApiError && error.slug === "return-damaged-needs-inspection") {
    return "검수 소견이 있어야 상품 훼손으로 거절할 수 있습니다.";
  }
  return "처리하지 못했습니다. 화면을 새로 고친 뒤 다시 시도해 주세요.";
}
