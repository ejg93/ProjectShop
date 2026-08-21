"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { ApiError, api } from "@/lib/api";

import { actionPath } from "@/lib/order-text";

import { ORDER_ACTIONS } from "../status";

/**
 * 이 묶음에 지금 할 수 있는 것.
 *
 * <p><b>목록을 화면이 만들지 않는다</b>(`11c-3b`). 서버가 상태와 권한을 같이 보고 내려준
 * {@code allowed_actions} 를 그대로 그린다 — 화면이 상태로 판단하면 규칙이 두 벌이 되고,
 * 그때 어긋난 쪽 버튼은 <b>눌러야 403</b> 이 난다(`D20`).
 *
 * <p><b>모르는 동작은 건너뛴다</b>(`D5` 「모르는 열거값은 무시한다」).
 * 서버가 동작을 하나 늘려도 빈 버튼이 생기지 않고, 화면 배포를 기다릴 필요도 없다.
 *
 * <p><b>셋 다 확인을 받는다</b>(`D20` 「되돌릴 수 없는 조작」). 브라우저 기본 대화상자를 쓴다 —
 * 초점 가두기와 키보드 처리를 우리가 다시 만들 이유가 없고, 여기서 물을 것은 예·아니오뿐이다.
 * 문구는 <b>무엇이 사라지는지</b>를 적는다.
 */
export function OrderActions({
  sellerOrderNumber,
  allowedActions,
}: {
  sellerOrderNumber: string;
  allowedActions: string[];
}) {
  const router = useRouter();
  const [failure, setFailure] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [sending, setSending] = useState<string | null>(null);
  const [refreshing, startRefresh] = useTransition();

  const actions = allowedActions.filter((action) => ORDER_ACTIONS[action]);
  const busy = sending !== null || refreshing;

  if (actions.length === 0) {
    return null;
  }

  async function run(action: string) {
    const defined = ORDER_ACTIONS[action];
    if (!window.confirm(defined.confirm)) {
      return;
    }

    setSending(action);
    setFailure(null);
    setNotice(null);

    try {
      await api(actionPath(sellerOrderNumber, action), { method: "POST" });
      setNotice(`${defined.label} 처리가 끝났습니다.`);

      // 상태가 바뀌면 할 수 있는 것도 바뀐다. 화면이 직접 고치면 서버가 아는 것과 갈린다.
      startRefresh(() => router.refresh());
    } catch (error) {
      setFailure(messageFor(error));
    } finally {
      setSending(null);
    }
  }

  return (
    <div className="grid gap-2 border-t border-border pt-3">
      <div className="flex flex-wrap gap-2">
        {actions.map((action) => (
          <button
            key={action}
            type="button"
            onClick={() => run(action)}
            disabled={busy}
            className="
              rounded-ui border border-border px-3 py-1.5 text-sm
              transition-colors duration-200
              hover:border-accent-text
              focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
              disabled:opacity-60
            "
          >
            {sending === action ? "처리 중" : ORDER_ACTIONS[action].label}
          </button>
        ))}
      </div>

      {/* 결과가 소리로도 전해져야 한다(`D20` 「동적으로 바뀌는 것」) */}
      <p role="status" className="text-xs text-text-muted">
        {notice}
      </p>
      <p role="alert" className="text-xs text-danger-text">
        {failure}
      </p>
    </div>
  );
}

/**
 * 실패를 사람이 읽는 말로. <b>`slug` 로 갈린다</b>(`D5`·`D20`).
 */
function messageFor(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return "잠시 후 다시 시도해 주세요.";
  }

  switch (error.slug) {
    case "order-transition-not-allowed":
      return "지금 상태에서는 할 수 없는 처리입니다. 새로고침해 주세요.";
    case "withdrawal-period-expired":
      return "청약철회 기간이 지났습니다.";
    case "withdrawal-restricted":
      return "청약철회가 제한된 상품이 들어 있습니다.";
    case "seller-order-not-found":
      return "이미 처리된 주문입니다. 새로고침해 주세요.";
    default:
      return "처리하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
}
