import type { Metadata } from "next";

import { ComingSoon } from "@/components/coming-soon";
import { apiSession } from "@/lib/api-session";

import { ProductForm } from "./product-form";

export const metadata: Metadata = { title: "상품 등록 · ProjectShop" };

type Membership = { sellerId: number; sellerName: string };

/**
 * 상품 등록(`13f-1`).
 *
 * <p><b>`13f` 가 깐 자리표시를 여기서 지운다.</b> 그때는 「서버에 만드는 입구가 없다」고 적었는데
 * <b>그것이 틀렸다</b> — `/api/products` 에 `POST` 가 있고 셀러 목록만 조회 전용이었다.
 * `SellerProductController` 만 보고 판단한 결과다.
 *
 * <p><b>어느 셀러로 올리나를 서버가 정한다.</b> 화면이 `sellerId` 를 고르게 두면
 * 남의 셀러 번호를 넣어 볼 수 있고, 그것을 막는 것이 화면이 되면 <b>판정이 두 곳</b>이 된다.
 * 소속이 하나면 그것으로 정하고, 여럿이면 아직 못 고르므로 준비 중으로 둔다.
 */
export default async function SellerProductNewPage() {
  const memberships = await apiSession<Membership[]>("/api/me/sellers");

  if (memberships.length !== 1) {
    return (
      <ComingSoon
        title="상품 등록"
        detail={
          memberships.length === 0
            ? "셀러 소속이 있어야 상품을 등록하실 수 있습니다."
            : "여러 셀러에 속한 분의 등록 화면을 준비하고 있습니다."
        }
      />
    );
  }

  return (
    <>
      <div className="grid gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">상품 등록</h1>
        <p className="text-sm text-text-muted">
          등록하면 작성 중 상태가 됩니다. 검수를 신청해야 판매가 시작됩니다.
        </p>
      </div>

      <ProductForm sellerId={memberships[0].sellerId} />
    </>
  );
}
