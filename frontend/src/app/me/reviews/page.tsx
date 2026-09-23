import type { Metadata } from "next";
import Link from "next/link";

import { apiSession } from "@/lib/api-session";
import { dateText } from "@/lib/format";
import { REVIEW_REASON_TEXT, type ReviewReason } from "@/lib/review-text";

import { MyReviewActions } from "./my-review-actions";
import { MyReviewPhotos, type MyReviewPhoto } from "./my-review-photos";

export const metadata: Metadata = { title: "내 후기 · ProjectShop" };

/** 내가 쓴 후기 한 줄(`Q167`). 내려갔으면 그 시각과 사유가 같이 온다 */
type MyReview = {
  reviewId: number;
  productId: number;
  productName: string;
  rating: number;
  body: string;
  reply: string | null;
  createdAt: string;
  blockedAt: string | null;
  blockedReason: ReviewReason | null;
  photos: MyReviewPhoto[];
};

type Page = { items: MyReview[]; page: number; size: number; total: number };

/**
 * 내 후기(`Q171`).
 *
 * <p><b>내려간 후기도 보인다.</b> 공개한 운영정책이 「내려가면 그 사실과 사유를 내 후기에서 확인할 수
 * 있다」고 알린다(`D2` `R27`) — 상품 목록에서는 빠지지만 쓴 사람에게서 감출 이유가 없고,
 * 여기서 안 보이면 이의를 낼 근거도 모른다.
 *
 * <p><b>고치기·지우기가 여기 있다.</b> 상품 상세는 쿠키 없이 부르는 공개 목록이라 「내가 쓴 것인가」를
 * 모른다(`Q160`) — 세션을 싣는 이 화면이 그 자리다.
 */
export default async function MyReviewsPage() {
  const page = await apiSession<Page>("/api/me/reviews?size=50");

  return (
    <div className="mx-auto grid w-full max-w-3xl flex-1 content-start gap-8 px-4 py-16">
      <div className="grid gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">내 후기</h1>
        <p className="text-sm text-text-muted">
          쓰신 후기 {page.total}건입니다. 후기는 주문 상세에서 쓰실 수 있습니다.
        </p>
      </div>

      {page.items.length === 0 ? (
        <p className="rounded-ui border border-border bg-surface-raised p-5 text-sm text-text-muted">
          아직 쓰신 후기가 없습니다.
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
                <span className="text-text-muted">{dateText(item.createdAt)}</span>
              </div>

              {item.blockedAt === null || item.blockedReason === null ? null : (
                <div role="status" className="grid gap-1 rounded-ui border border-danger-text p-4">
                  <p className="font-semibold text-danger-text">
                    게시가 중단된 후기입니다 · {dateText(item.blockedAt)}
                  </p>
                  <p>사유: {REVIEW_REASON_TEXT[item.blockedReason]}</p>
                  <p className="text-text-muted">
                    이의가 있으시면{" "}
                    <Link href="/me/inquiries" className="underline">
                      내 문의
                    </Link>
                    의 「불만·분쟁 접수」로 알려 주세요.
                    <br />
                    관리자가 다시 확인하고, 판단이 바뀌면 후기를 다시 게시합니다.
                  </p>
                </div>
              )}

              <p className="whitespace-pre-wrap">{item.body}</p>

              {item.reply === null ? null : (
                <div className="grid gap-1 rounded-ui bg-surface p-4">
                  <p className="text-text-muted">판매자 답글</p>
                  <p className="whitespace-pre-wrap">{item.reply}</p>
                </div>
              )}

              <MyReviewPhotos reviewId={item.reviewId} photos={item.photos} />

              <MyReviewActions
                reviewId={item.reviewId}
                rating={item.rating}
                body={item.body}
                blocked={item.blockedAt !== null}
              />
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
