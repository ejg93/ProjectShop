import Link from "next/link";

import { Td, Th } from "@/components/table-cells";
import { dateTimeText, priceText } from "@/lib/format";
import { paymentStatusText } from "@/lib/order-text";

/** 관리자 목록 한 줄(`Q176`). <b>구매자가 안 온다</b> — 누가 샀나는 상세의 필드 그룹이 가른다(`4d`) */
export type AdminOrder = {
  orderNumber: string;
  status: string;
  payableAmount: number;
  itemCount: number;
  createdAt: string;
};

export type AdminOrderPage = { items: AdminOrder[]; page: number; size: number; total: number };

/**
 * 화면이 받는 조건. <b>끝날은 그날을 넣는다</b> — 사람은 「23일까지」를 23일 포함으로 읽는다.
 * 서버의 {@code to} 는 그다음 날이라 {@link apiQueryOf} 가 하루를 민다.
 */
export type AdminOrderFilter = { status: string; from: string; until: string };

/** 결제 층 상태(`D7`). 서버가 모르는 값은 400 이라 여기 없는 것을 보내지 않는다 */
export const ORDER_STATUSES = ["PAYMENT_PENDING", "PAID", "PAYMENT_EXPIRED", "PAYMENT_FAILED"] as const;

const DATE = /^\d{4}-\d{2}-\d{2}$/;

/**
 * 주소의 조건을 거른다. 모르는 상태·날짜 꼴이 아닌 값은 빈 값이 된다 — 그대로 보내면 서버가 400 을 주고,
 * 화면은 조건을 고칠 자리 대신 오류를 그린다.
 */
export function filterOf(raw: { status?: string; from?: string; until?: string }): AdminOrderFilter {
  const status = ORDER_STATUSES.find((known) => known === raw.status) ?? "";
  const from = raw.from && DATE.test(raw.from) ? raw.from : "";
  const until = raw.until && DATE.test(raw.until) ? raw.until : "";
  return { status, from, until };
}

/** 기간이 거꾸로면 서버가 400 이다(빈 기간). 화면이 먼저 알려 준다 */
export function isReversed(filter: AdminOrderFilter): boolean {
  return filter.from !== "" && filter.until !== "" && filter.from > filter.until;
}

/** 서버에 보낼 조건. 끝날을 하루 밀어 {@code to}(제외)로 바꾼다 */
export function apiQueryOf(filter: AdminOrderFilter): Record<string, string> {
  const query: Record<string, string> = {};
  if (filter.status) {
    query.status = filter.status;
  }
  if (filter.from) {
    query.from = filter.from;
  }
  if (filter.until) {
    const [year, month, day] = filter.until.split("-").map(Number);
    query.to = new Date(Date.UTC(year, month - 1, day + 1)).toISOString().slice(0, 10);
  }
  return query;
}

/**
 * 거르는 칸(`Q176`). <b>조건은 주소에 둔다</b>(`D24`) — 새로 고치거나 링크를 보내도 같은 목록이 선다.
 * 그래서 {@code method="get"} 폼이고 상태를 들지 않는다.
 */
export function OrderFilters({ filter }: { filter: AdminOrderFilter }) {
  const field = "rounded-ui border border-border bg-surface px-3 py-2";

  return (
    <form method="get" className="flex flex-wrap items-end gap-3 text-sm">
      <div className="grid gap-1">
        <label htmlFor="order-status" className="text-xs text-text-muted">
          결제 상태
        </label>
        <select id="order-status" name="status" defaultValue={filter.status} className={field}>
          <option value="">전부</option>
          {ORDER_STATUSES.map((status) => (
            <option key={status} value={status}>
              {paymentStatusText(status)}
            </option>
          ))}
        </select>
      </div>
      <div className="grid gap-1">
        <label htmlFor="order-from" className="text-xs text-text-muted">
          주문일 시작
        </label>
        <input id="order-from" type="date" name="from" defaultValue={filter.from} className={field} />
      </div>
      <div className="grid gap-1">
        <label htmlFor="order-until" className="text-xs text-text-muted">
          주문일 끝 (그날 포함)
        </label>
        <input id="order-until" type="date" name="until" defaultValue={filter.until} className={field} />
      </div>
      <button type="submit" className="rounded-ui border border-border px-4 py-2 font-medium">
        보기
      </button>
    </form>
  );
}

/**
 * 주문을 줄로 그린다. <b>표다</b> — 여러 건을 훑는 자리라 칸이 세로로 맞아야 한다(`D20` 밀도 7).
 * 받은 주문(셀러)과 같은 판단이고, 좁은 화면에서는 가로로 민다.
 */
export function OrderTable({ items }: { items: AdminOrder[] }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[40rem] border-collapse text-sm">
        <caption className="sr-only">주문 목록. 주문번호, 주문 시각, 결제 상태, 상품 수, 결제 금액 순</caption>
        <thead>
          <tr className="border-b border-border text-left text-xs text-text-muted">
            <Th>주문번호</Th>
            <Th>주문 시각</Th>
            <Th>결제 상태</Th>
            <Th align="right">상품</Th>
            <Th align="right">결제 금액</Th>
          </tr>
        </thead>
        <tbody>
          {items.map((order) => (
            <tr key={order.orderNumber} className="border-b border-border">
              <Td>
                <Link
                  href={`/admin/orders/${encodeURIComponent(order.orderNumber)}`}
                  className="
                    font-mono text-accent-text underline underline-offset-4
                    focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
                  "
                >
                  {order.orderNumber}
                </Link>
              </Td>
              <Td muted>{dateTimeText(order.createdAt)}</Td>
              <Td>{paymentStatusText(order.status)}</Td>
              <Td align="right">{order.itemCount}종</Td>
              <Td align="right">{priceText(order.payableAmount)}</Td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
