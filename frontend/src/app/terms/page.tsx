import type { Metadata } from "next";

import { ComingSoon } from "@/components/coming-soon";

export const metadata: Metadata = { title: "이용약관 · ProjectShop" };

/** 자리표시. 청크 13a 가 문안을 쓰면 그것을 그린다. */
export default function TermsPage() {
  return (
    <ComingSoon
      title="이용약관"
      detail="서비스를 쓰실 때 적용되는 약속입니다. 아직 준비 중입니다."
    />
  );
}
