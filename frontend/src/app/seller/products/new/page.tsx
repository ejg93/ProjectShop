import type { Metadata } from "next";

import { ComingSoon } from "@/components/coming-soon";

export const metadata: Metadata = { title: "상품 등록 · ProjectShop" };

/**
 * 상품 등록(`13f`).
 *
 * <p><b>화면이 아니라 자리표시다.</b> 서버에 상품을 만드는 입구가 없다 —
 * {@code /api/seller/products} 는 목록만 내린다. 화면만 그리면 <b>눌러도 아무 일이 안 나는 폼</b>이 되고,
 * 그것은 없는 화면보다 나쁘다.
 *
 * <p>`13c` 가 {@link ComingSoon} 을 만들어 두고 아무 데서도 안 써서
 * `D20` 의 「아직 없는 화면은 준비 중으로」가 <b>셀러 경로에 한 번도 안 걸려 있었다.</b>
 * 여기가 그 첫 자리다.
 *
 * <p><b>입구가 생기면 이 파일이 폼으로 바뀐다.</b> 남아 있으면 그 청크가 안 끝난 것이다.
 */
export default function SellerProductNewPage() {
  return (
    <ComingSoon
      title="상품 등록"
      detail="등록 기능을 준비하고 있습니다. 지금은 등록된 상품의 상태와 재고만 확인하실 수 있습니다."
    />
  );
}
