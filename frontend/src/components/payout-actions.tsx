"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { ApiError, api } from "@/lib/api";

/**
 * 이 정산서에 지금 할 수 있는 것(`20-1`).
 *
 * <p><b>서버가 준 목록으로 그린다</b>(`Q81`). 정산 응답의 {@code allowedActions} 에 그 이름이
 * 없으면 버튼을 <b>안 그린다</b> — 그려 놓고 감추는 방식은 안 쓴다. 감추면 그 자리가
 * DOM 에 남아서 화면낭독기가 읽고, 개발자 도구로 되살리면 눌러진다(`D20`·`59` 와 같은 판단).
 *
 * <p><b>{@link OrderActions} 와 같은 꼴이다</b>(`Q81`). 권한·상태·<b>누가 올렸나</b>를 서버가
 * 한자리에서 보고 이름 목록으로 준다 — 화면에 판단이 없다.
 *
 * <p><b>그전에는 화면이 셋째를 못 봤다.</b> 자기가 올린 지급은 자기가 승인 못 하는데
 * ({@code settlement_payout_self_approval_check}), 누가 올렸는지가 응답에 없어서
 * <b>승인 버튼이 뜨고 누르면 403 이 왔다.</b> 지금은 그 버튼이 애초에 안 온다 —
 * {@link messageFor} 의 그 문구는 다른 경로(경쟁 상태)로만 나온다.
 */

/**
 * 버튼 하나.
 *
 * @param permission 이것이 열려 있어야 버튼을 그린다. 자원은 {@code settlement} 하나다
 * @param path       {@code /api/settlements/<번호>/} 뒤에 붙는 것
 * @param confirm    누르기 전에 무엇이 일어나는지 묻는다(`D20` 「되돌릴 수 없는 조작」)
 */
export type PayoutAction = {
  /** 서버가 주는 이름. 소문자·하이픈으로 바꾸면 아래 {@link PayoutAction#path} 다 */
  name: string;
  path: string;
  label: string;
  confirm: string;
};

/**
 * 지급을 올린다. <b>반려된 것도 이 버튼으로 다시 온다.</b>
 *
 * <p>되돌릴 수 있는 조작이라 문구가 무엇이 사라지는지를 안 묻는다.
 */
const REQUEST: PayoutAction = {
  name: "PAYOUT_REQUEST",
  path: "payout-request",
  label: "지급 올리기",
  confirm: "이 정산서를 승인 대기로 올립니다. 계속하시겠습니까?",
};

/** 승인. <b>여기서 돈이 나간 것으로 친다</b> */
const APPROVE: PayoutAction = {
  name: "PAYOUT",
  path: "payout",
  label: "지급 승인",
  confirm: "승인하시면 지급한 것으로 기록되며 되돌릴 수 없습니다. 계속하시겠습니까?",
};

/** 반려. 돈이 안 나가므로 다시 올릴 수 있다 */
const REJECT: PayoutAction = {
  name: "PAYOUT_REJECTION",
  path: "payout-rejection",
  label: "지급 반려",
  confirm: "반려하시면 이 정산서가 지급 대기로 돌아갑니다. 계속하시겠습니까?",
};

/**
 * 이 상태에서 열리는 것.
 *
 * <p><b>상태와 권한을 둘 다 본다.</b> 권한만 보면 이미 지급한 정산서에 승인 버튼이 뜨고,
 * 상태만 보면 볼 권한밖에 없는 셀러에게 지급 버튼이 뜬다.
 *
 * <p><b>줄 돈이 없으면 못 올린다</b>({@code settlement_payout_amount_check}).
 * 지급액이 0 이하인 정산서는 이월로 넘어가지 지급 대상이 아니라, 그 버튼을 그리면
 * <b>눌러야 422 가 나는 버튼</b>이 된다.
 *
 * @param granted 판정이 내려준 목록. 범위는 안 본다 — 대상별 판정은 서버가 한다
 */
export function payoutActionsFor(allowed: readonly string[]): PayoutAction[] {
  return ALL.filter((action) => allowed.includes(action.name));
}

/** 서버가 주는 이름과 짝이 되는 표. 이름이 곧 경로라 여기서 다시 안 만든다. */
const ALL: PayoutAction[] = [REQUEST, APPROVE, REJECT];

export function PayoutActions({
  settlementNumber,
  actions,
}: {
  settlementNumber: string;
  actions: PayoutAction[];
}) {
  const router = useRouter();
  const [failure, setFailure] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [sending, setSending] = useState<string | null>(null);
  const [refreshing, startRefresh] = useTransition();

  const busy = sending !== null || refreshing;

  if (actions.length === 0) {
    return null;
  }

  async function run(chosen: PayoutAction) {
    if (!window.confirm(chosen.confirm)) {
      return;
    }

    setSending(chosen.path);
    setFailure(null);
    setNotice(null);

    try {
      await api(`/api/settlements/${settlementNumber}/${chosen.path}`, { method: "POST" });
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
        {actions.map((action) => (
          <button
            key={action.path}
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
            {sending === action.path ? "처리 중" : action.label}
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

/** 실패를 사람이 읽는 말로. <b>`slug` 로 갈린다</b>(`D5`·`D20`) */
function messageFor(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return "잠시 후 다시 시도해 주세요.";
  }

  switch (error.slug) {
    case "settlement-self-approval":
      return "본인이 올린 지급은 본인이 승인할 수 없습니다. 다른 관리자에게 요청해 주세요.";
    case "settlement-already-decided":
      return "이미 처리된 정산서입니다. 새로고침해 주세요.";
    case "settlement-nothing-to-pay":
      return "지급할 금액이 없는 정산서입니다.";
    case "settlement-not-found":
      return "정산서를 찾을 수 없습니다. 새로고침해 주세요.";
    default:
      return "처리하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
}
