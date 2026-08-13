import type { Metadata } from "next";

import { ComingSoon } from "@/components/coming-soon";

export const metadata: Metadata = { title: "상품 · ProjectShop" };

/** 자리표시. 청크 14 가 목록으로 바꾼다. */
export default function ProductsPage() {
  return (
    <ComingSoon
      title="상품"
      detail="파는 물건을 훑어보는 화면입니다. 아직 준비 중입니다."
    />
  );
}
