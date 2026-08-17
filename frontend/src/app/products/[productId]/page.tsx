import type { Metadata } from "next";

import { ComingSoon } from "@/components/coming-soon";

export const metadata: Metadata = { title: "상품 상세 · ProjectShop" };

/**
 * 자리표시. 청크 `14b` 가 상세로 바꾼다.
 *
 * <p>목록의 카드가 여기로 온다. 링크를 안 걸어 두면 `14b` 가 링크 걸 자리를 다시 찾게 되고,
 * 걸어 두고 404 를 주면 사용자가 고장으로 본다(`D20` 「아직 없는 화면은 자리표시로 둔다」).
 */
export default function ProductDetailPage() {
  return (
    <ComingSoon
      title="상품 상세"
      detail="옵션을 고르고 장바구니에 담는 화면입니다. 아직 준비 중입니다."
    />
  );
}
