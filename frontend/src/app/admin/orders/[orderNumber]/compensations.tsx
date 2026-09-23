"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { ApiError, api } from "@/lib/api";
import { dateTimeText, priceText } from "@/lib/format";

/** 판정 한 줄(`43a-4c`). 사유는 3년이라 판정(5년)보다 먼저 사라진다 — 그 뒤에는 비어 있다(`V69`) */
export type CompensationEntry = {
  kind: string;
  bearer: string;
  amount: number;
  reason: string | null;
  inquiryNumber: string | null;
  decidedAt: string;
};

export type CompensationListing = { items: CompensationEntry[]; allowedActions: string[] };

/** 소비자분쟁해결기준 별표2 「인터넷쇼핑몰업」의 갈래(`V69`) */
const KIND_TEXT: Record<string, string> = {
  NON_DELIVERY: "미인도",
  LATE_DELIVERY: "지연 인도",
};

/** 누가 무나. 셀러가 무는 것만 그 달 정산서에서 빠진다 */
const BEARER_TEXT: Record<string, string> = {
  SELLER: "셀러 부담",
  PLATFORM: "플랫폼 부담",
};

/**
 * 손해배상 판정(`43a-4c`, `D2` R38·R39).
 *
 * <p><b>금액을 화면이 계산하지 않는다</b> — 법이 액수를 안 정해서 사람이 정하고, <b>사유가 그 근거라 필수다</b>.
 * 판정 칸은 서버가 {@code COMPENSATE} 를 줄 때만 선다({@code allowedActions}) — 감사자는 목록만 본다.
 */
export function Compensations({
  sellerOrderNumber,
  listing,
}: {
  sellerOrderNumber: string;
  listing: CompensationListing;
}) {
  return (
    <div className="grid gap-2">
      <p className="font-semibold">손해배상</p>
      {listing.items.length === 0 ? (
        <p className="text-text-muted">판정한 배상이 없습니다.</p>
      ) : (
        <ul className="grid gap-1">
          {listing.items.map((entry, index) => (
            <li key={`${entry.decidedAt}-${index}`} className="grid gap-0.5">
              <span>
                {KIND_TEXT[entry.kind] ?? entry.kind} · {BEARER_TEXT[entry.bearer] ?? entry.bearer} ·{" "}
                <span className="tabular-nums">{priceText(entry.amount)}</span>
                <span className="text-text-muted"> · {dateTimeText(entry.decidedAt)}</span>
              </span>
              <span className="text-text-muted">
                {entry.reason ?? "사유 보존 기간이 지났습니다"}
                {entry.inquiryNumber ? ` (문의 ${entry.inquiryNumber})` : null}
              </span>
            </li>
          ))}
        </ul>
      )}
      {listing.allowedActions.includes("COMPENSATE") ? (
        <CompensationForm sellerOrderNumber={sellerOrderNumber} />
      ) : null}
    </div>
  );
}

function CompensationForm({ sellerOrderNumber }: { sellerOrderNumber: string }) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const [failure, setFailure] = useState<string | null>(null);
  const id = (name: string) => `comp-${name}-${sellerOrderNumber}`;
  const field = "rounded-ui border border-border bg-surface px-3 py-2";

  const submit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const inquiryNumber = String(form.get("inquiryNumber") ?? "").trim();
    setFailure(null);
    startTransition(async () => {
      try {
        await api(`/api/shipments/${encodeURIComponent(sellerOrderNumber)}/compensate`, {
          method: "POST",
          body: {
            kind: form.get("kind"),
            bearer: form.get("bearer"),
            amount: Number(form.get("amount")),
            basis: form.get("basis"),
            inquiryNumber: inquiryNumber === "" ? null : inquiryNumber,
          },
        });
        router.refresh();
      } catch (error) {
        setFailure(messageOf(error));
      }
    });
  };

  return (
    <form onSubmit={submit} className="grid gap-3 rounded-ui border border-border p-4">
      <div className="flex flex-wrap items-end gap-3">
        <div className="grid gap-1">
          <label htmlFor={id("kind")} className="text-xs text-text-muted">
            무엇에 대한 배상
          </label>
          <select id={id("kind")} name="kind" className={field}>
            <option value="NON_DELIVERY">미인도</option>
            <option value="LATE_DELIVERY">지연 인도</option>
          </select>
        </div>
        <div className="grid gap-1">
          <label htmlFor={id("bearer")} className="text-xs text-text-muted">
            누가 무나
          </label>
          <select id={id("bearer")} name="bearer" className={field}>
            <option value="SELLER">셀러 (정산에서 뺍니다)</option>
            <option value="PLATFORM">플랫폼</option>
          </select>
        </div>
        <div className="grid gap-1">
          <label htmlFor={id("amount")} className="text-xs text-text-muted">
            금액(원)
          </label>
          <input id={id("amount")} name="amount" type="number" required min={1} max={100000000} className={field} />
        </div>
        <div className="grid gap-1">
          <label htmlFor={id("inquiry")} className="text-xs text-text-muted">
            문의 번호 (선택)
          </label>
          <input id={id("inquiry")} name="inquiryNumber" maxLength={17} placeholder="Q-20260923-ABCDEF" className={field} />
        </div>
      </div>
      <div className="grid gap-1">
        <label htmlFor={id("basis")} className="text-xs text-text-muted">
          왜 그 금액인가 (필수, 분쟁 때 근거가 됩니다)
        </label>
        {/* 상한을 상수로 안 뺀다 — `ScreenLengthTest` 가 이 숫자를 서버 `@Size` 와 맞댄다 */}
        <textarea id={id("basis")} name="basis" required maxLength={2000} rows={3} className={field} />
      </div>
      <div>
        <button
          type="submit"
          disabled={pending}
          className="rounded-ui border border-border px-4 py-2 font-medium disabled:opacity-50"
        >
          배상 판정 남기기
        </button>
      </div>
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
    case "compensation-inquiry-mismatch":
      return "그 문의는 이 배송 묶음의 것이 아닙니다. 문의 번호를 확인해 주세요.";
    case "validation-failed":
      return "금액과 사유를 확인해 주세요. 문의 번호는 Q-날짜-여섯 자리 꼴입니다.";
    default:
      return "처리하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
}
