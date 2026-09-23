import type { Metadata } from "next";

import { Pager, pageNumberOf } from "@/components/pager";
import { apiSession } from "@/lib/api-session";

import { type AdminOrderPage, OrderFilters, OrderTable, apiQueryOf, filterOf, isReversed } from "./order-list";

export const metadata: Metadata = { title: "주문 조회 · ProjectShop" };

const PAGE_SIZE = 50;

/**
 * 관리자 주문 조회(`Q176`).
 *
 * <p><b>모든 주문이 여기 선다</b> — {@code order:read} 를 {@code all} 로 가진 사람(관리자·감사자)만 부르는 입구다
 * ({@code GET /api/admin/orders}). 셀러의 주문 목록은 {@code /seller/orders} 고, 「내 주문」은 산 사람 것만이다.
 *
 * <p><b>쓰기가 없다.</b> 강제 전이(`16c`)와 손해배상 접수(`43a-4c`)가 상세에 붙는다.
 */
export default async function AdminOrdersPage({
  searchParams,
}: {
  searchParams: Promise<{ page?: string; status?: string; from?: string; until?: string }>;
}) {
  const requested = await searchParams;
  const filter = filterOf(requested);
  const reversed = isReversed(filter);

  const query = new URLSearchParams({
    page: String(pageNumberOf(requested.page)),
    size: String(PAGE_SIZE),
    ...apiQueryOf(filter),
  });
  const result = reversed ? null : await apiSession<AdminOrderPage>(`/api/admin/orders?${query}`);

  return (
    <>
      <div className="grid gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">주문 조회</h1>
        <p className="text-sm text-text-muted">
          모든 주문을 최근 것부터 봅니다. 받는 분·결제 정보는 주문을 열면 권한에 맞게 보입니다.
        </p>
      </div>

      <OrderFilters filter={filter} />

      {result === null ? (
        <p role="alert" className="text-sm text-danger-text">
          시작일이 끝일보다 늦습니다. 기간을 다시 골라 주세요.
        </p>
      ) : result.items.length > 0 ? (
        <>
          <OrderTable items={result.items} />
          <Pager
            page={result.page}
            lastPage={Math.max(0, Math.ceil(result.total / result.size) - 1)}
            total={result.total}
            basePath="/admin/orders"
            label="주문 목록"
            unit="건"
            params={{ status: filter.status || undefined, from: filter.from || undefined, until: filter.until || undefined }}
          />
        </>
      ) : (
        <p className="rounded-ui border border-border bg-surface-raised p-5 text-sm text-text-muted">
          조건에 맞는 주문이 없습니다.
        </p>
      )}
    </>
  );
}
