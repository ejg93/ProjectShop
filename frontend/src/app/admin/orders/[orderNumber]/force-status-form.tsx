"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { ApiError, api } from "@/lib/api";
import { shipmentStatusText } from "@/lib/order-text";

/**
 * 관리자 강제 전이(`16c`).
 *
 * <p><b>갈 곳을 서버가 고른다</b>({@code forcibleStatuses}) — 화면이 상태를 보고 판단하면 서버의 표와 두 벌이 된다.
 * 목록이 비면 이 폼이 아예 안 선다.
 *
 * <p><b>사유가 필수고 한 번 더 묻는다.</b> 전이표 밖의 이동이라 되돌리는 동작이 없다 — 끝난 상태에서 나가는 길을
 * 안 열었다(2026-09-23 사용자 선택). 잘못 누른 것을 고칠 수 없으니 누르기 전에 멈춘다.
 */
export function ForceStatusForm({
  sellerOrderNumber,
  forcibleStatuses,
}: {
  sellerOrderNumber: string;
  forcibleStatuses: string[];
}) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const [to, setTo] = useState(forcibleStatuses[0] ?? "");
  const [reason, setReason] = useState("");
  const [confirming, setConfirming] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  if (forcibleStatuses.length === 0) {
    return null;
  }

  const ask = (event: React.FormEvent) => {
    event.preventDefault();
    setFailure(null);
    setConfirming(true);
  };

  const send = () => {
    startTransition(async () => {
      try {
        await api(`/api/shipments/${encodeURIComponent(sellerOrderNumber)}/force-status`, {
          method: "POST",
          body: { to, reason },
        });
        setConfirming(false);
        setReason("");
        router.refresh();
      } catch (error) {
        setConfirming(false);
        setFailure(messageOf(error));
      }
    });
  };

  return (
    <form onSubmit={ask} className="grid gap-3 rounded-ui border border-border p-4 text-sm">
      <p className="font-semibold">상태 강제 변경</p>
      <div className="flex flex-wrap items-end gap-3">
        <div className="grid gap-1">
          <label htmlFor={`force-to-${sellerOrderNumber}`} className="text-xs text-text-muted">
            옮길 상태
          </label>
          <select
            id={`force-to-${sellerOrderNumber}`}
            name="to"
            value={to}
            onChange={(event) => setTo(event.target.value)}
            className="rounded-ui border border-border bg-surface px-3 py-2"
          >
            {forcibleStatuses.map((status) => (
              <option key={status} value={status}>
                {shipmentStatusText(status)}
              </option>
            ))}
          </select>
        </div>
        <div className="grid min-w-64 flex-1 gap-1">
          <label htmlFor={`force-reason-${sellerOrderNumber}`} className="text-xs text-text-muted">
            사유 (필수, 처리 내역에 남습니다)
          </label>
          {/* 상한을 상수로 안 뺀다 — `ScreenLengthTest` 가 이 숫자를 서버 `@Size` 와 맞댄다 */}
          <input
            id={`force-reason-${sellerOrderNumber}`}
            name="reason"
            required
            maxLength={500}
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            className="rounded-ui border border-border bg-surface px-3 py-2"
          />
        </div>
      </div>

      {confirming ? (
        <div className="flex flex-wrap items-center gap-2">
          <span>
            「{shipmentStatusText(to)}」(으)로 옮깁니다. 되돌릴 수 없습니다. 진행하시겠습니까?
          </span>
          <button
            type="button"
            disabled={pending}
            onClick={send}
            className="rounded-ui border border-danger-text px-3 py-1 text-xs font-medium text-danger-text disabled:opacity-50"
          >
            옮기기
          </button>
          <button
            type="button"
            onClick={() => setConfirming(false)}
            className="rounded-ui px-3 py-1 text-xs text-text-muted"
          >
            그만두기
          </button>
        </div>
      ) : (
        <div>
          <button type="submit" className="rounded-ui border border-border px-4 py-2 font-medium">
            강제로 옮기기
          </button>
        </div>
      )}

      <p role="alert" className="text-danger-text">
        {failure}
      </p>
    </form>
  );
}

/** 서버가 준 오류를 화면 문구로. <b>`slug` 로 갈린다</b>(`D5`·`D20`) */
function messageOf(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return "서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
  switch (error.slug) {
    case "transition-reason-required":
      return "사유를 적어 주세요.";
    case "order-transition-not-allowed":
      return "지금 상태에서는 그곳으로 옮길 수 없습니다. 화면을 새로 고쳐 주세요.";
    case "seller-order-not-found":
      return "이 묶음을 옮길 권한이 없거나 묶음이 없습니다.";
    default:
      return "처리하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
}
