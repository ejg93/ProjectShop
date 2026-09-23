import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";

import { ApiError } from "@/lib/api";
import { apiSession } from "@/lib/api-session";

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

  return (
    <>
      <Link href="/admin/orders" className="text-sm text-text-muted underline underline-offset-4">
        주문 조회로
      </Link>
      <OrderDetailView order={order} />
    </>
  );
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
