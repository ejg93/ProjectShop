import type { Metadata } from "next";
import Link from "next/link";

import { apiSession } from "@/lib/api-session";
import { dateText } from "@/lib/format";

import { AnswerForm } from "./answer-form";

export const metadata: Metadata = { title: "받은 문의 · ProjectShop" };

/** 셀러가 보는 문의 한 줄(`59`). 계정에 붙는 요구는 여기 안 나온다 */
type SellerInquiry = {
  inquiryNumber: string;
  productId: number | null;
  productName: string | null;
  question: string;
  answer: string | null;
  status: "RECEIVED" | "ANSWERED" | "BLOCKED" | "WITHDRAWN";
  isPublic: boolean;
  createdAt: string;
  answeredAt: string | null;
};

type Page = { items: SellerInquiry[]; page: number; size: number; total: number };

const STATUS_TEXT: Record<SellerInquiry["status"], string> = {
  RECEIVED: "답변 대기",
  ANSWERED: "답변 완료",
  BLOCKED: "게시 중단됨",
  WITHDRAWN: "고객이 거두었음",
};

/**
 * 받은 문의(`59-1`).
 *
 * <p><b>계정에 붙는 요구는 여기 안 나온다.</b> 처리정지·이의제기·분쟁은 개인정보처리자인
 * 우리에게 온 것이라 셀러가 볼 것이 아니고, <b>목록의 조건이 상품의 셀러라 애초에 안 걸린다</b> —
 * 화면이 거르는 것이 아니라 규칙 하나로 성립한다(`59`).
 *
 * <p><b>비공개 문의도 보인다.</b> 답해야 하는 사람이라 봐야 한다 —
 * 대신 그 글이 공개 Q&A 에는 안 나간다.
 */
export default async function SellerInquiriesPage() {
  const page = await apiSession<Page>("/api/seller/inquiries?size=50");

  const waiting = page.items.filter((item) => item.status === "RECEIVED").length;

  return (
    <div className="grid gap-8">
      <div className="grid gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">받은 문의</h1>
        <p className="text-sm text-text-muted">
          내 상품에 달린 문의입니다. 답변 대기 {waiting}건 · 전체 {page.total}건
        </p>
      </div>

      {page.items.length === 0 ? (
        <p className="rounded-ui border border-border bg-surface-raised p-5 text-sm text-text-muted">
          아직 받은 문의가 없습니다.
        </p>
      ) : (
        <ul className="grid gap-4">
          {page.items.map((item) => (
            <li
              key={item.inquiryNumber}
              className="grid gap-3 rounded-ui border border-border bg-surface-raised p-5 text-sm"
            >
              <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                <span className="font-semibold">{STATUS_TEXT[item.status]}</span>
                {item.isPublic ? null : <span className="text-text-muted">비공개</span>}
                <span className="text-text-muted">{dateText(item.createdAt)}</span>
              </div>

              {item.productId === null ? null : (
                <Link
                  href={`/products/${item.productId}`}
                  className="text-text-muted underline"
                >
                  {item.productName}
                </Link>
              )}

              <p className="whitespace-pre-wrap">{item.question}</p>

              {item.answer === null ? null : (
                <div className="grid gap-1 rounded-ui bg-surface p-4">
                  <p className="text-text-muted">
                    내 답변
                    {item.answeredAt === null ? null : ` · ${dateText(item.answeredAt)}`}
                  </p>
                  <p className="whitespace-pre-wrap">{item.answer}</p>
                </div>
              )}

              {/* 답할 수 있는 것은 아직 답이 안 나간 것뿐이다 — 서버가 조건부 UPDATE 로 막는다 */}
              {item.status === "RECEIVED" ? (
                <AnswerForm inquiryNumber={item.inquiryNumber} />
              ) : null}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
