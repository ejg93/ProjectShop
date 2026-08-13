import type { Metadata } from "next";

import { ComingSoon } from "@/components/coming-soon";

export const metadata: Metadata = { title: "개인정보처리방침 · ProjectShop" };

/** 자리표시. 청크 13a 가 문안을 쓰면 그것을 그린다. */
export default function PrivacyPage() {
  return (
    <ComingSoon
      title="개인정보처리방침"
      detail="어떤 정보를 무엇에 쓰고 언제 지우는지 알려 드리는 곳입니다. 아직 준비 중입니다."
    />
  );
}
