import type { Metadata } from "next";
import type { ReactNode } from "react";
import Link from "next/link";

import { PolicyBody } from "@/components/policy-document";
import { apiPublic } from "@/lib/api";

import { SignupForm } from "./signup-form";
import type { ConsentItem } from "./signup-form";

export const metadata: Metadata = { title: "회원가입 · ProjectShop" };

/** 목록에는 본문이 없다(`13d-1`). 펼칠 항목만 따로 받는다 */
type ConsentDetail = ConsentItem & { body: string | null };

/**
 * 회원가입(`13d-2`).
 *
 * <p><b>동의 항목을 화면에 안 박는다</b>(`13d-1`). 무엇을 물어야 하는지는 서버가 알려주고,
 * 항목이 늘어도 이 화면은 안 고친다 — `V11` 이 항목을 데이터로 둔 이유가 그것이다.
 *
 * <p><b>본문을 서버가 그린다.</b> 마크다운 렌더러를 클라이언트로 내리면 그만큼 브라우저로
 * 내려가는데, 약관은 사용자가 누르는 것이 아니라 읽는 것이라 그럴 이유가 없다(`D24`).
 * 그려진 것을 폼에 넘긴다.
 *
 * <p><b>`R16` ①③ 의 마지막 칸이 여기다</b>(점검 A) — 약관규제법 제3조제1항의 강조 표시와
 * 제3항의 설명이 이 화면에서 성립한다.
 */
export default async function SignupPage() {
  const items = await apiPublic<ConsentItem[]>("/api/consent-items");

  // 항목마다 본문을 받는다. 목록이 본문을 안 내리므로 여기서 채운다.
  // 순서대로 기다리면 항목 수만큼 왕복이 쌓여서 한꺼번에 보낸다.
  const details = await Promise.all(
    items.map((item) => apiPublic<ConsentDetail>(`/api/consent-items/${item.code}`)),
  );

  const bodies: Record<string, ReactNode> = {};
  for (const detail of details) {
    if (detail.body) {
      bodies[detail.code] = <PolicyBody>{detail.body}</PolicyBody>;
    }
  }

  return (
    <div className="mx-auto grid w-full max-w-xl flex-1 content-start gap-8 px-4 py-16">
      <div className="grid gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">회원가입</h1>
        <p className="text-sm text-text-muted">
          이미 계정이 있으시면{" "}
          <Link
            href="/login"
            className="
              font-semibold text-accent-text underline underline-offset-4
              focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
            "
          >
            로그인
          </Link>
          해 주세요.
        </p>
      </div>

      <SignupForm items={items} bodies={bodies} />
    </div>
  );
}
