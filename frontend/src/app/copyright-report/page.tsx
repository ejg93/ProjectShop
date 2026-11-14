import type { Metadata } from "next";
import { notFound } from "next/navigation";

import { ApiError } from "@/lib/api";
import { apiPublic } from "@/lib/api-session";
import { pageQuery } from "@/lib/paging";

import { CopyrightReportForm } from "./report-form";

export const metadata: Metadata = { title: "저작권 침해 신고 · ProjectShop" };

type ProductDetail = {
  productId: number;
  name: string;
  imageUrls: string[];
  imageIds: number[];
};

/** 중단·재개 요구를 받는 수령인(`Q463`). 넣지 않은 칸은 `null` 이다 */
type Recipient = {
  name: string | null;
  department: string | null;
  email: string | null;
  phone: string | null;
  address: string | null;
};

/** 공개 후기 목록에서 사진만 쓴다(`Q196`) */
type ReviewPage = { items: { photos: { reviewImageId: number; thumbnailUrl: string }[] }[] };

/**
 * 저작권 침해 신고(`Q183`, 저작권법 제103조, `D2` `R42`).
 *
 * <p><b>로그인 없이 받는다.</b> 권리자가 우리 회원일 이유가 없다 — 회원만 신고하게 하면 법이 요구한 절차에 가입이라는
 * 관문이 하나 붙는다(`SecurityConfig` 의 공개 목록, `Q94`).
 *
 * <p><b>`Q94` 가 입구를 세우고 화면을 안 세웠다.</b> 절차가 있어야 책임 제한을 받는데(제102조) 입구가 화면에 없으면
 * 권리자가 그 절차를 모른다.
 */
export default async function CopyrightReportPage({
  searchParams,
}: {
  searchParams: Promise<{ productId?: string }>;
}) {
  const productId = Number((await searchParams).productId);
  if (!Number.isInteger(productId) || productId <= 0) {
    notFound();
  }

  let product: ProductDetail;
  try {
    product = await apiPublic<ProductDetail>(`/api/products/${productId}`);
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      notFound();
    }
    throw error;
  }

  const images = product.imageIds.map((imageId, index) => ({ imageId, url: product.imageUrls[index] }));
  // 후기 사진도 같은 절차를 받는다(`Q196`). 공개 후기 목록이 싣는 사진이 곧 신고할 수 있는 표면이다.
  const reviews = await apiPublic<ReviewPage>(`/api/products/${productId}/reviews?${pageQuery(0, 50)}`);
  const recipient = await apiPublic<Recipient>("/api/copyright-reports/recipient");
  const reviewImages = reviews.items.flatMap((review) =>
    review.photos.map((photo) => ({ reviewImageId: photo.reviewImageId, url: photo.thumbnailUrl })),
  );

  return (
    <div className="mx-auto grid w-full max-w-2xl flex-1 content-start gap-8 px-4 py-16">
      <div className="grid gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">저작권 침해 신고</h1>
        <p className="text-sm text-text-muted">
          「{product.name}」의 상품 사진이나 후기 사진이 권리를 침해한다면 알려 주세요. 로그인하지 않아도 됩니다.
          <br />
          관리자가 확인한 뒤 게시를 중단하거나 기각합니다. 연락드릴 수 있게 이메일을 적어 주세요.
          <br />
          게시가 중단되면 신고하신 메일로 알려 드립니다.
        </p>
      </div>

      <RecipientBlock recipient={recipient} />

      {images.length === 0 && reviewImages.length === 0 ? (
        <p className="text-sm text-text-muted">이 상품에는 신고할 사진이 없습니다.</p>
      ) : (
        <CopyrightReportForm images={images} reviewImages={reviewImages} />
      )}
    </div>
  );
}

/**
 * 중단·재개 요구를 받는 수령인(저작권법 제103조④ · 시행령 제44조 — 성명·소속, 전화·전자우편, 우편 주소).
 *
 * <p><b>값 있는 칸만 그린다.</b> 전부 비면 비었다고 쓴다 — 지정되지 않은 것을 숨기면 공지한 척이 된다.
 */
function RecipientBlock({ recipient }: { recipient: Recipient }) {
  const person = recipient.name === null ? null
    : recipient.department === null ? recipient.name : `${recipient.name}(${recipient.department})`;
  const empty = [recipient.name, recipient.email, recipient.phone, recipient.address].every((value) => value === null);

  return (
    <section aria-labelledby="copyright-recipient" className="grid gap-1 rounded-ui border border-border p-4 text-sm">
      <h2 id="copyright-recipient" className="font-semibold">중단·재개 요구를 받는 수령인</h2>
      {empty ? (
        <p className="text-text-muted">수령인이 아직 지정되지 않았습니다.</p>
      ) : (
        <ul className="grid gap-0.5">
          {person === null ? null : <li>{person}</li>}
          {recipient.email === null ? null : (
            <li><a href={`mailto:${recipient.email}`} className="underline">{recipient.email}</a></li>
          )}
          {recipient.phone === null ? null : (
            <li><a href={`tel:${recipient.phone}`} className="underline">{recipient.phone}</a></li>
          )}
          {recipient.address === null ? null : <li>{recipient.address}</li>}
        </ul>
      )}
    </section>
  );
}
