"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { ApiError, api } from "@/lib/api";

/**
 * 상품 상태를 옮기는 버튼(`Q182`).
 *
 * <p><b>이름은 서버가 준다</b>({@code allowedActions}) — 서버가 전이표와 판정을 돌려 고른 것이라 화면은 상태를 보고
 * 버튼을 고르지 않는다. <b>여기 없는 이름은 버튼이 안 난다</b> — 서버가 새 동작을 내려도 화면이 안 깨진다.
 *
 * <p>셀러 목록과 관리자 검수 목록이 같이 쓴다. 누가 무엇을 받는지는 서버가 이미 갈랐다.
 */
type ActionSpec = {
  label: string;
  path: string;
  /** 사유를 받아야 하는 동작. 칸 이름이 곧 요청 본문의 키다 */
  reasonField?: "note" | "reason";
  reasonLabel?: string;
  /** 차단 풀기는 되돌릴 곳을 고른다 */
  unblock?: boolean;
};

const ACTIONS: Record<string, ActionSpec> = {
  SUBMIT_REVIEW: { label: "검수 요청", path: "submit-review" },
  SUSPEND: { label: "판매 쉬기", path: "suspend" },
  RESUME: { label: "판매 다시 열기", path: "resume" },
  APPROVE: { label: "승인", path: "approve" },
  REJECT: { label: "반려", path: "reject", reasonField: "note", reasonLabel: "반려 사유" },
  BLOCK: { label: "판매 차단", path: "block", reasonField: "reason", reasonLabel: "차단 사유" },
  UNBLOCK: { label: "차단 풀기", path: "unblock", unblock: true },
};

/** 이름 목록을 버튼으로. 모르는 이름은 버린다 */
export function productActionsFor(names: string[]): (ActionSpec & { name: string })[] {
  return names.filter((name) => name in ACTIONS).map((name) => ({ name, ...ACTIONS[name] }));
}

export function ProductActions({ productId, allowedActions }: { productId: number; allowedActions: string[] }) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const [open, setOpen] = useState<string | null>(null);
  const [failure, setFailure] = useState<string | null>(null);

  const actions = productActionsFor(allowedActions);
  if (actions.length === 0) {
    return null;
  }

  const send = (spec: ActionSpec, body?: Record<string, unknown>) => {
    setFailure(null);
    startTransition(async () => {
      try {
        await api(`/api/products/${productId}/${spec.path}`, { method: "POST", body });
        setOpen(null);
        router.refresh();
      } catch (error) {
        setFailure(messageOf(error));
      }
    });
  };

  const opened = actions.find((action) => action.name === open);

  return (
    <div className="grid gap-2">
      {opened ? (
        <form
          className="grid gap-2"
          onSubmit={(event) => {
            event.preventDefault();
            const data = new FormData(event.currentTarget);
            if (opened.unblock) {
              send(opened, { backToSale: data.get("backToSale") === "true" });
            } else if (opened.reasonField) {
              send(opened, { [opened.reasonField]: String(data.get(opened.reasonField)) });
            }
          }}
        >
          {opened.reasonField ? (
            <label className="grid gap-1 text-xs">
              <span>{opened.reasonLabel}</span>
              {/* 상한을 상수로 안 뺀다 — `ScreenLengthTest` 가 이 숫자를 서버 `@Size` 와 맞댄다 */}
              <textarea name={opened.reasonField} required maxLength={500} rows={2}
                        className="rounded-ui border border-border bg-surface px-2 py-1" />
            </label>
          ) : (
            <fieldset className="grid gap-1 text-xs">
              <legend>푼 뒤에</legend>
              <label><input type="radio" name="backToSale" value="true" defaultChecked /> 바로 판매로(오인이었다)</label>
              <label><input type="radio" name="backToSale" value="false" /> 초안으로(고쳐서 다시 검수)</label>
            </fieldset>
          )}
          <div className="flex gap-2">
            <button type="submit" disabled={pending}
                    className="rounded-ui border border-border px-3 py-1 text-xs font-medium disabled:opacity-50">
              {opened.label}
            </button>
            <button type="button" onClick={() => setOpen(null)} className="px-3 py-1 text-xs text-text-muted">
              그만두기
            </button>
          </div>
        </form>
      ) : (
        <div className="flex flex-wrap gap-2">
          {actions.map((action) => (
            <button
              key={action.name}
              type="button"
              disabled={pending}
              onClick={() => (action.reasonField || action.unblock ? setOpen(action.name) : send(action))}
              className="rounded-ui border border-border px-3 py-1 text-xs font-medium disabled:opacity-50"
            >
              {action.label}
            </button>
          ))}
        </div>
      )}
      <p role="alert" className="text-xs text-danger-text">
        {failure}
      </p>
    </div>
  );
}

/** 서버가 준 오류를 화면 문구로. <b>`slug` 로 갈린다</b>(`D5`·`D20`) */
function messageOf(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return "서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
  switch (error.slug) {
    case "product-transition-not-allowed":
      return "지금 상태에서는 할 수 없습니다. 화면을 새로 고쳐 주세요.";
    case "product-without-sku":
      return "팔 조합(옵션과 가격)이 하나도 없어서 검수를 요청할 수 없습니다.";
    case "seller-not-verified":
      return "셀러 신원정보가 확인되지 않아 판매로 옮길 수 없습니다.";
    case "product-forbidden":
      return "이 상품을 다룰 권한이 없습니다.";
    default:
      return "처리하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
}
