import type { Metadata } from "next";
import Link from "next/link";

import { ProductActions } from "@/components/product-actions";
import { apiSession } from "@/lib/api-session";
import { dateText } from "@/lib/format";
import { productStatusText } from "@/lib/product-text";

export const metadata: Metadata = { title: "상품 검수 · ProjectShop" };

type Status = "PENDING_REVIEW" | "ON_SALE" | "BLOCKED";

type AdminProduct = {
  productId: number;
  sellerId: number;
  name: string;
  status: string;
  createdAt: string;
  allowedActions: string[];
};

type Page = { items: AdminProduct[]; page: number; size: number; total: number };

const TABS: { status: Status; label: string }[] = [
  { status: "PENDING_REVIEW", label: "검수 대기" },
  { status: "ON_SALE", label: "판매 중" },
  { status: "BLOCKED", label: "판매 차단" },
];

/**
 * 상품 검수(`Q182`).
 *
 * <p><b>상태 입구 일곱이 있는데 부르는 화면이 없었다</b> — 셀러가 화면에서 만든 상품이 초안에 머물러 판매로 못 갔다.
 * 관리자는 셀러 목록 입구로 전체가 보여서({@code all}) 그 입구를 상태로 걸러 쓴다.
 *
 * <p><b>버튼은 서버가 고른다</b>({@code allowedActions}). 승인 전 신원 확인(`D2` R1)처럼 버튼을 눌러야 드러나는
 * 거절은 서버가 사유와 함께 돌려준다.
 */
export default async function AdminProductsPage({
  searchParams,
}: {
  searchParams: Promise<{ status?: string }>;
}) {
  const requested = (await searchParams).status;
  const status: Status = TABS.some((tab) => tab.status === requested) ? (requested as Status) : "PENDING_REVIEW";
  const page = await apiSession<Page>(`/api/seller/products?status=${status}&size=50`);

  return (
    <div className="grid gap-8">
      <div className="grid gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">상품 검수</h1>
        <p className="text-sm text-text-muted">
          셀러가 검수를 요청한 상품을 승인하거나 반려합니다. 판매 중인 상품에 문제가 드러나면 판매를 막습니다.
        </p>
      </div>

      <nav aria-label="상품 상태" className="flex gap-2 text-sm">
        {TABS.map((tab) => (
          <Link
            key={tab.status}
            href={`/admin/products?status=${tab.status}`}
            aria-current={tab.status === status ? "page" : undefined}
            className={`rounded-ui border px-3 py-1.5 ${tab.status === status ? "border-accent font-semibold" : "border-border"}`}
          >
            {tab.label}
          </Link>
        ))}
      </nav>

      {page.items.length === 0 ? (
        <p className="rounded-ui border border-border bg-surface-raised p-5 text-sm text-text-muted">
          이 상태의 상품이 없습니다.
        </p>
      ) : (
        <ul className="grid gap-4">
          {page.items.map((product) => (
            <li
              key={product.productId}
              className="grid gap-3 rounded-ui border border-border bg-surface-raised p-5 text-sm"
            >
              <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                <span className="font-semibold">{product.name}</span>
                <span className="text-text-muted">{productStatusText(product.status)}</span>
                <span className="text-text-muted">등록 {dateText(product.createdAt)}</span>
              </div>
              <ProductActions productId={product.productId} allowedActions={product.allowedActions} />
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
