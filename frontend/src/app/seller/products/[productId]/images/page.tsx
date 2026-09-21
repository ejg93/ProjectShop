import type { Metadata } from "next";
import Link from "next/link";

import { apiSession } from "@/lib/api-session";

import { ImageManager, type ProductImage } from "./image-manager";

export const metadata: Metadata = { title: "상품 사진 · ProjectShop" };

/**
 * 내 상품의 사진(`Q140`).
 *
 * <p><b>이 화면이 답하는 물음은 「이 상품에 무엇이 걸려 있나」다.</b> 그전에는 판매자가
 * 사진을 올릴 자리가 아예 없어서 사람이 {@code curl} 로 밀어 넣었고, 사진이 없는 상품은
 * 목록에서 남의 스톡 사진(`picsum`)으로 그려졌다.
 *
 * <p><b>자리가 상품 상세가 아니라 그 아래 `images` 다.</b> 셀러 상세를 내주는 입구가 서버에 없어서
 * (`SellerProductController` 는 목록과 사진 셋뿐이다) 상세를 가장하면 상품 이름조차 못 그린다.
 * 지금 있는 계약이 답하는 것만 그리고, 상세가 생기면 그때 그 아래로 들어간다.
 *
 * <p><b>읽기는 서버가 한다</b>(`D24`). 올리고 지우는 것만 잎사귀 하나가 클라이언트다.
 */
export default async function SellerProductImagesPage({
  params,
}: {
  params: Promise<{ productId: string }>;
}) {
  const { productId } = await params;
  const images = await apiSession<ProductImage[]>(`/api/seller/products/${productId}/images`);

  return (
    <div className="grid gap-4">
      <div className="grid gap-1">
        <h1 className="text-lg font-semibold">상품 사진</h1>
        <p className="text-sm text-text-muted">
          상품 번호 {productId}. 첫 번째 사진이 목록의 대표 이미지로 나갑니다.
        </p>
        <Link
          href="/seller/products"
          className="
            text-sm underline-offset-4 hover:text-accent-text hover:underline
            focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
          "
        >
          내 상품으로 돌아가기
        </Link>
      </div>

      <ImageManager productId={Number(productId)} images={images} />
    </div>
  );
}
