import type { Metadata } from "next";
import Link from "next/link";

import { apiSession } from "@/lib/api-session";
import { dateText } from "@/lib/format";

import { ReplyForm } from "./reply-form";

export const metadata: Metadata = { title: "받은 후기 · ProjectShop" };

/** 셀러가 보는 후기 한 줄(`Q167`). 답이 없으면 `reply` 가 `null` 이다 */
type SellerReview = {
  reviewId: number;
  productId: number;
  productName: string;
  writerName: string;
  rating: number;
  body: string;
  reply: string | null;
  createdAt: string;
};

type Page = { items: SellerReview[]; page: number; size: number; total: number };

/**
 * 받은 후기(`Q171`).
 *
 * <p><b>답만 한다.</b> 셀러는 후기를 내리지 못한다 — 불리한 후기를 내리는 자리가 되기 때문이고,
 * 공개한 운영정책도 「셀러는 후기를 삭제할 수 없습니다. 답글로만 의견을 밝힐 수 있습니다」라고
 * 알린다(`D2` `R27`). 부적절한 후기는 상품 화면의 신고로 관리자에게 보낸다.
 *
 * <p><b>내려간 후기는 안 나온다.</b> 공개 목록과 같다 — 답할 대상이 아니다.
 */
export default async function SellerReviewsPage() {
  const page = await apiSession<Page>("/api/seller/reviews?size=50");

  const unanswered = page.items.filter((item) => item.reply === null).length;

  return (
    <div className="grid gap-8">
      <div className="grid gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">받은 후기</h1>
        <p className="text-sm text-text-muted">
          내 상품에 달린 후기입니다. 답글 없음 {unanswered}건 · 전체 {page.total}건
          <br />
          답글은 상품 화면에 후기와 함께 공개됩니다. 후기를 지우실 수는 없습니다.
        </p>
      </div>

      {page.items.length === 0 ? (
        <p className="rounded-ui border border-border bg-surface-raised p-5 text-sm text-text-muted">
          아직 받은 후기가 없습니다.
        </p>
      ) : (
        <ul className="grid gap-4">
          {page.items.map((item) => (
            <li
              key={item.reviewId}
              className="grid gap-3 rounded-ui border border-border bg-surface-raised p-5 text-sm"
            >
              <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                <Link href={`/products/${item.productId}`} className="font-semibold underline">
                  {item.productName}
                </Link>
                <span aria-label={`별점 ${item.rating}점`}>{"★".repeat(item.rating)}</span>
                <span className="text-text-muted">
                  {item.writerName} · {dateText(item.createdAt)}
                </span>
              </div>

              <p className="whitespace-pre-wrap">{item.body}</p>

              <ReplyForm reviewId={item.reviewId} reply={item.reply} />
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
