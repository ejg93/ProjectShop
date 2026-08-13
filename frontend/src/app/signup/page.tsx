import type { Metadata } from "next";

import { ComingSoon } from "@/components/coming-soon";

export const metadata: Metadata = { title: "회원가입 · ProjectShop" };

/** 자리표시. 청크 13d 가 가입 화면으로 바꾼다. */
export default function SignupPage() {
  return (
    <ComingSoon
      title="회원가입"
      detail="계정을 만드는 화면입니다. 아직 준비 중입니다."
    />
  );
}
