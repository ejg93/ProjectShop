import type { Metadata } from "next";
import { notFound } from "next/navigation";

import { ApiError, apiPublic } from "@/lib/api";

import { CopyrightReportForm } from "./report-form";

export const metadata: Metadata = { title: "저작권 침해 신고 · ProjectShop" };

type ProductDetail = {
  productId: number;
  name: string;
  imageUrls: string[];
  imageIds: number[];
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
  const reviews = await apiPublic<ReviewPage>(`/api/products/${productId}/reviews?size=50`);
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
        </p>
      </div>

      {images.length === 0 && reviewImages.length === 0 ? (
        <p className="text-sm text-text-muted">이 상품에는 신고할 사진이 없습니다.</p>
      ) : (
        <CopyrightReportForm images={images} reviewImages={reviewImages} />
      )}
    </div>
  );
}
