import Link from "next/link";

import { apiPublic } from "@/lib/api";
import { apiSessionOptional } from "@/lib/api-session";
import { dateText } from "@/lib/format";
import { can, type Me } from "@/lib/permissions";

import { ReportButton } from "./report-button";

/**
 * 후기 한 줄(`Q160`).
 *
 * <p><b>쓴 사람의 주소가 없다.</b> 공개 글이라 이름만 나가고, 연락처를 붙이면
 * <b>그 글이 곧 주소록</b>이 된다 — 서버가 계약에서 뺐다.
 */
type Review = {
  reviewId: number;
  writerName: string;
  rating: number;
  body: string;
  /** 셀러 답글. 없으면 null(`48`) */
  reply: string | null;
  createdAt: string;
  /** 지금 보는 사람이 쓴 것인가. 로그인 안 했으면 전부 거짓 */
  mine: boolean;
  /** 사진. 목록은 썸네일로 그리고 누르면 원본을 연다(`Q159`). 둘 다 만료 5분 서명 URL 이다 */
  photos: { thumbnailUrl: string; originalUrl: string }[];
};

type ReviewPage = {
  items: Review[];
  page: number;
  size: number;
  total: number;
  /** 후기가 없으면 average 가 null 이다 — 「별 0점」과 「후기 없음」을 가른다 */
  summary: { count: number; average: number | null };
};

/** 한 쪽에 몇 개. 서버 기본값과 같게 둔다(`D5` 「목록」) */
const PAGE_SIZE = 10;

/**
 * 상품의 후기(`Q160`).
 *
 * <p><b>로그인 없이 읽는다.</b> 살까 말까 하는 사람이 읽는 자리라 로그인을 요구하면
 * 그 자리가 닫힌다 — 공개 Q&A 와 같은 판단이다(`59-1`).
 *
 * <p><b>내려간 후기와 지운 후기는 애초에 안 뽑힌다.</b> 화면이 거르는 것이 아니라
 * 목록 API 가 고르는 조건으로 뺀다 — 화면에서 숨기면 API 로는 보인다.
 *
 * <p><b>쓰는 자리를 여기 안 둔다.</b> 후기는 <b>산 주문 줄</b>에 붙는 것이라 어느 주문인지를
 * 알아야 하고, 그것을 아는 화면은 주문 상세다(`/orders/[orderNumber]`).
 * 상품 상세에 쓰기 칸을 두면 「무엇을 샀는지 고르는 칸」이 같이 생긴다.
 */
export async function ProductReviews({ productId }: { productId: number }) {
  const page = await apiPublic<ReviewPage>(
    `/api/products/${productId}/reviews?page=0&size=${PAGE_SIZE}`,
  );
  // 신고는 로그인한 사람만 한다(`Q171`). 목록은 공개 입구로 읽고(`Q170`), 세션으로는
  // 버튼을 그릴지만 묻는다 — 머리글(`site-header`)이 같은 목록을 같은 방법으로 읽는다.
  const canReport = can(await apiSessionOptional<Me>("/api/me/permissions"), "review", "report");

  return (
    <section className="grid gap-4 border-t border-border pt-8">
      <div className="flex flex-wrap items-baseline gap-3">
        <h2 className="text-lg font-semibold tracking-tight">상품 후기</h2>
        {page.summary.count > 0 ? (
          <p className="text-sm text-text-muted">
            <span className="font-medium text-text">
              {page.summary.average!.toFixed(1)}
            </span>{" "}
            · {page.summary.count}개
          </p>
        ) : null}
        {/*
          전자상거래법 제21조의4 가 요구하는 것은 기준을 정하는 것이 아니라 **알리는 것**이다
          (`D2` `R27`). 후기를 읽는 자리에서 그 규칙으로 갈 수 있어야 알린 것이 된다 —
          발에만 두면 규칙이 있다는 것을 모른 채로 후기를 읽는다.
        */}
        <p className="ml-auto text-sm">
          <Link
            href="/review-policy"
            className="
              text-text-muted underline underline-offset-4 hover:text-accent-text
              focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
            "
          >
            후기 운영정책
          </Link>
        </p>
      </div>

      {page.items.length > 0 ? (
        <ul className="grid gap-4">
          {page.items.map((review) => (
            <li key={review.reviewId} className="grid gap-2 border-b border-border pb-4">
              <div className="flex flex-wrap items-baseline gap-2 text-sm">
                <span aria-label={`별점 ${review.rating}점`}>
                  {"★".repeat(review.rating)}
                  <span className="text-text-muted">{"★".repeat(5 - review.rating)}</span>
                </span>
                <span className="text-text-muted">{review.writerName}</span>
                <span className="text-xs text-text-muted">{dateText(review.createdAt)}</span>
                {review.mine ? (
                  <span className="text-xs text-text-muted">(내가 쓴 후기)</span>
                ) : null}
              </div>

              <p className="whitespace-pre-wrap text-sm">{review.body}</p>

              {review.photos.length === 0 ? null : (
                <ul className="flex flex-wrap gap-2">
                  {review.photos.map((photo, index) => (
                    <li key={photo.thumbnailUrl}>
                      <a href={photo.originalUrl} target="_blank" rel="noreferrer">
                        {/* eslint-disable-next-line @next/next/no-img-element */}
                        <img
                          src={photo.thumbnailUrl}
                          alt={`${review.writerName} 님의 후기 사진 ${index + 1}`}
                          className="h-20 w-20 rounded-ui border border-border object-cover"
                        />
                        <span className="sr-only">(새 창에서 원본 열기)</span>
                      </a>
                    </li>
                  ))}
                </ul>
              )}

              {review.reply ? (
                <div className="grid gap-1 rounded-ui border border-border bg-surface-raised px-3 py-2">
                  <p className="text-xs font-medium">판매자 답글</p>
                  <p className="whitespace-pre-wrap text-sm">{review.reply}</p>
                </div>
              ) : null}

              {canReport ? <ReportButton reviewId={review.reviewId} /> : null}
            </li>
          ))}
        </ul>
      ) : (
        <p className="text-sm text-text-muted">
          아직 후기가 없습니다. 이 상품을 받으신 뒤 주문 내역에서 후기를 남기실 수 있습니다.
        </p>
      )}
    </section>
  );
}
