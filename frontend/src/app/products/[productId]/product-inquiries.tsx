import Link from "next/link";

import { apiPublic } from "@/lib/api";
import { dateText } from "@/lib/format";

import { AskForm } from "./ask-form";

/**
 * 공개 Q&A 한 줄(`59`). <b>낸 사람을 실을 칸이 아예 없다</b> —
 * 아무나 보는 자리라 「누가 물었나」를 실으면 그 사람이 무엇을 샀는지가 드러난다.
 */
type PublicEntry = {
  inquiryNumber: string;
  question: string;
  answer: string | null;
  status: "RECEIVED" | "ANSWERED" | "BLOCKED" | "WITHDRAWN";
  createdAt: string;
  answeredAt: string | null;
};

type Page = { items: PublicEntry[]; page: number; size: number; total: number };

/**
 * 상품의 공개 문의(`59-1`).
 *
 * <p><b>로그인 없이 읽는다.</b> 구매 전 문의라 <b>살까 말까 하는 사람</b>이 읽는 자리고,
 * 로그인을 요구하면 그 자리가 닫힌다 — 서버가 이 목록을 비로그인에 열어 둔 이유와 같다.
 *
 * <p><b>비공개와 내려간 글은 애초에 안 뽑힌다.</b> 화면이 거르는 것이 아니라
 * 목록 API 가 고르는 조건으로 뺀다 — 화면에서 숨기면 API 로는 보인다(`59`).
 *
 * <p><b>묻는 것은 로그인이 필요하다.</b> 그 조각만 클라이언트로 뗐다(`D24`).
 */
export async function ProductInquiries({ productId }: { productId: number }) {
  const page = await apiPublic<Page>(`/api/products/${productId}/inquiries?size=20`);

  return (
    <section aria-labelledby="inquiry-heading" className="grid gap-4 border-t border-border pt-6">
      <h2 id="inquiry-heading" className="text-sm font-semibold">
        상품 문의 {page.total}건
      </h2>

      <AskForm productId={productId} />

      {page.items.length === 0 ? (
        <p className="text-sm text-text-muted">아직 등록된 문의가 없습니다.</p>
      ) : (
        <ul className="grid gap-3">
          {page.items.map((item) => (
            <li
              key={item.inquiryNumber}
              className="grid gap-2 rounded-ui border border-border bg-surface-raised p-4 text-sm"
            >
              <p className="text-text-muted">{dateText(item.createdAt)}</p>
              <p className="whitespace-pre-wrap">{item.question}</p>

              {item.answer === null ? (
                <p className="text-text-muted">답변을 기다리고 있습니다.</p>
              ) : (
                <div className="grid gap-1 rounded-ui bg-surface p-3">
                  <p className="text-text-muted">
                    판매자 답변
                    {item.answeredAt === null ? null : ` · ${dateText(item.answeredAt)}`}
                  </p>
                  <p className="whitespace-pre-wrap">{item.answer}</p>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}

      <p className="text-sm text-text-muted">
        보내신 문의는 <Link href="/me/inquiries" className="underline">내 문의</Link>에서 보실 수
        있습니다.
      </p>
    </section>
  );
}
