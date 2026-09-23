import type { Metadata } from "next";
import Link from "next/link";

import { apiSession } from "@/lib/api-session";
import { dateText, dateTimeText, priceText } from "@/lib/format";
import { refundReasonText, refundStatusText } from "@/lib/refund-text";

import { RefundDecision } from "./refund-decision";

export const metadata: Metadata = { title: "환불 처리 · ProjectShop" };

type Status = "REQUESTED" | "APPROVED" | "REJECTED";

type Refund = {
  refundNumber: string;
  sellerOrderNumber: string;
  orderNumber: string;
  status: string;
  reasonCode: string;
  amount: number;
  dueAt: string;
  /** 기한을 넘겼나. 서버가 판단한 값이라 화면이 다시 재지 않는다(`D2` R5) */
  overdue: boolean;
  createdAt: string;
  allowedActions: string[];
};

type Page = { items: Refund[]; page: number; size: number; total: number };

const TABS: { status: Status; label: string }[] = [
  { status: "REQUESTED", label: "승인 대기" },
  { status: "APPROVED", label: "환불 완료" },
  { status: "REJECTED", label: "반려됨" },
];

/**
 * 환불 대기열(`Q185`).
 *
 * <p><b>승인·반려 입구가 있는데 부르는 화면이 없었다</b> — 환급 기한(3영업일, 전자상거래법 제18조제2항, `D2` R5)을
 * 넘기는지 볼 자리가 없었다. <b>서버의 기본 정렬이 기한 임박순이라</b> 그대로 받아 그린다 — 이 화면의 물음은
 * 「무엇이 새로 왔나」가 아니라 「무엇이 늦고 있나」다({@code RefundQuery}).
 *
 * <p><b>주문 상세가 아니라 대기열에 둔다.</b> 기한 관리는 여러 건을 한눈에 보는 일이고, 주문 상세에 두면 늦은 건을 찾으려고
 * 주문을 하나씩 열어야 한다.
 */
export default async function AdminRefundsPage({
  searchParams,
}: {
  searchParams: Promise<{ status?: string }>;
}) {
  const requested = (await searchParams).status;
  const status: Status = TABS.some((tab) => tab.status === requested) ? (requested as Status) : "REQUESTED";
  const page = await apiSession<Page>(`/api/refunds?status=${status}&size=50`);

  return (
    <div className="grid gap-8">
      <div className="grid gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">환불 처리</h1>
        <p className="text-sm text-text-muted">
          환급 기한이 급한 것부터 보입니다. 승인하면 결제 대행사로 바로 돌려주고, 반려하면 사유가 고객에게 갑니다.
        </p>
      </div>

      <nav aria-label="환불 상태" className="flex gap-2 text-sm">
        {TABS.map((tab) => (
          <Link
            key={tab.status}
            href={`/admin/refunds?status=${tab.status}`}
            aria-current={tab.status === status ? "page" : undefined}
            className={`rounded-ui border px-3 py-1.5 ${tab.status === status ? "border-accent font-semibold" : "border-border"}`}
          >
            {tab.label}
          </Link>
        ))}
      </nav>

      {page.items.length === 0 ? (
        <p className="rounded-ui border border-border bg-surface-raised p-5 text-sm text-text-muted">
          이 상태의 환불이 없습니다.
        </p>
      ) : (
        <ul className="grid gap-4">
          {page.items.map((refund) => (
            <li
              key={refund.refundNumber}
              className="grid gap-3 rounded-ui border border-border bg-surface-raised p-5 text-sm"
            >
              <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                <span className="font-semibold">{priceText(refund.amount)}</span>
                <span>{refundReasonText(refund.reasonCode)}</span>
                <span className="text-text-muted">{refundStatusText(refund.status)}</span>
                {refund.overdue ? (
                  <span className="rounded-ui border border-danger-text px-2 py-0.5 text-xs text-danger-text">
                    기한 지남
                  </span>
                ) : null}
              </div>
              <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-text-muted">
                <dt>환급 기한</dt>
                <dd>{dateText(refund.dueAt)}</dd>
                <dt>요청</dt>
                <dd>{dateTimeText(refund.createdAt)}</dd>
                <dt>주문</dt>
                <dd>
                  {refund.orderNumber} · {refund.sellerOrderNumber}
                </dd>
                <dt>환불 번호</dt>
                <dd>{refund.refundNumber}</dd>
              </dl>
              <RefundDecision
                refundNumber={refund.refundNumber}
                amount={refund.amount}
                allowedActions={refund.allowedActions}
              />
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
