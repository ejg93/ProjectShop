"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { ApiError, api } from "@/lib/api";

import { actionPath } from "@/lib/order-text";

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
 * <p><b>확인을 받는다</b>(`D20` 「되돌릴 수 없는 조작」). 브라우저 기본 대화상자를 쓴다 —
 * 초점 가두기와 키보드 처리를 우리가 다시 만들 이유가 없고, 여기서 물을 것은 예·아니오뿐이다.
 * 문구는 <b>무엇이 사라지는지</b>를 적는다.
 *
 * <p><b>라벨 표는 밖에서 받는다</b>(`13g`). 누르는 절차는 화면군이 같은데 <b>부르는 말이 다르다</b> —
 * 같은 묶음을 두고 사는 사람은 「반품 신청」을 하고 셀러는 「반품 완료」를 한다.
 * 표를 안에 박으면 화면군마다 이 파일을 통째로 베끼게 되고, 그러면 <b>눌렀을 때 무엇이
 * 일어나는지가 화면군마다 갈린다.</b>
 */

/**
 * 버튼 하나의 말.
 *
 * @param label   버튼에 쓰는 말
 * @param confirm 누르기 전에 무엇이 사라지는지 묻는다(`D20` 「되돌릴 수 없는 조작」)
 */
export type OrderAction = { label: string; confirm: string };

export function OrderActions({
  sellerOrderNumber,
  allowedActions,
  actions: defined,
}: {
  sellerOrderNumber: string;
  allowedActions: string[];
  actions: Record<string, OrderAction>;
}) {
  const router = useRouter();
  const [failure, setFailure] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [sending, setSending] = useState<string | null>(null);
  const [refreshing, startRefresh] = useTransition();

  const available = allowedActions.filter((action) => defined[action]);
  const busy = sending !== null || refreshing;

  if (available.length === 0) {
    return null;
  }

  async function run(action: string) {
    const chosen = defined[action];
    if (!window.confirm(chosen.confirm)) {
      return;
    }

    setSending(action);
    setFailure(null);
    setNotice(null);

    try {
      await api(actionPath(sellerOrderNumber, action), { method: "POST" });
      setNotice(`${chosen.label} 처리가 끝났습니다.`);

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
        {available.map((action) => (
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
            {sending === action ? "처리 중" : defined[action].label}
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
