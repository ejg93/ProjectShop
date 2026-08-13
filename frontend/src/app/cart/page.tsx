import type { Metadata } from "next";

import { ComingSoon } from "@/components/coming-soon";

export const metadata: Metadata = { title: "장바구니 · ProjectShop" };

/** 자리표시. 청크 15 가 장바구니로 바꾼다. */
export default function CartPage() {
  return (
    <ComingSoon
      title="장바구니"
      detail="담아 둔 물건을 모아 보는 화면입니다. 아직 준비 중입니다."
    />
  );
}
