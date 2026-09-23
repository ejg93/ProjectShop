import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";

import { ApiError } from "@/lib/api";
import { apiSession } from "@/lib/api-session";

import type { CompensationListing } from "./compensations";
import { type AdminOrderDetail, OrderDetailView } from "./order-detail";

export const metadata: Metadata = { title: "주문 상세 · ProjectShop" };

/**
 * 관리자 주문 상세(`Q176`). <b>산 사람의 상세와 같은 입구를 부른다</b>({@code GET /api/orders/{번호}}) —
 * 판정이 관리자에게 이미 열고 필드 그룹(`4d`)도 거기서 갈린다. 입구를 둘 두면 마스킹 규칙이 두 벌이 된다.
 *
 * <p>없는 번호와 못 보는 번호는 같은 404 다(`D5`).
 */
export default async function AdminOrderDetailPage({
  params,
}: {
  params: Promise<{ orderNumber: string }>;
}) {
  const { orderNumber } = await params;
  const order = await findOrder(orderNumber);
  const compensations = await compensationsOf(order.sellerOrders.map((bundle) => bundle.sellerOrderNumber));

  return (
    <>
      <Link href="/admin/orders" className="text-sm text-text-muted underline underline-offset-4">
        주문 조회로
      </Link>
      <OrderDetailView order={order} compensations={compensations} />
    </>
  );
}

/**
 * 묶음마다 배상 판정을 받는다(`43a-4c`). <b>조회 권한이 없으면 그 묶음을 뺀다</b> — 서버가 404 로 답하고(`D5`),
 * 그것은 오류가 아니라 「이 사람이 볼 것이 아니다」다. 묶음은 주문 하나에 몇 개라 따로 불러도 싸다.
 */
async function compensationsOf(sellerOrderNumbers: string[]): Promise<Record<string, CompensationListing>> {
  const listed = await Promise.all(
    sellerOrderNumbers.map(async (number) => {
      try {
        const listing = await apiSession<CompensationListing>(
          `/api/shipments/${encodeURIComponent(number)}/compensations`,
        );
        return [[number, listing] as const];
      } catch (error) {
        if (error instanceof ApiError && error.status === 404) {
          return [];
        }
        throw error;
      }
    }),
  );
  return Object.fromEntries(listed.flat());
}

async function findOrder(orderNumber: string): Promise<AdminOrderDetail> {
  try {
    return await apiSession<AdminOrderDetail>(`/api/orders/${encodeURIComponent(orderNumber)}`);
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      notFound();
    }
    throw error;
  }
}
