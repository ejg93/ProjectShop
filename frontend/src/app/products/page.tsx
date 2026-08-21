import type { Metadata } from "next";
import Image from "next/image";
import Link from "next/link";

import { Pager, pageNumberOf } from "@/components/pager";
import { apiPublic } from "@/lib/api";

export const metadata: Metadata = { title: "상품 · ProjectShop" };

/** 한 쪽에 몇 개. 서버 기본값과 같게 둔다(`D5` 「목록」) */
const PAGE_SIZE = 20;

/**
 * 공개 목록 한 쪽. 키 이름은 `api.ts` 가 카멜로 바꾼 뒤의 것이다.
 *
 * <p>수수료율과 재고가 없다. 서버가 공개용 record 를 따로 두고 아예 안 내린다(`8`).
 */
type ProductPage = {
  items: ProductItem[];
  page: number;
  size: number;
  total: number;
};

type ProductItem = {
  productId: number;
  sellerId: number;
  sellerName: string;
  name: string;
  minPriceInclVat: number;
  /** 이 셀러의 배송비. 총액을 그리려면 있어야 한다(`D2` R24) */
  shippingFee: number;
  createdAt: string;
};

/**
 * 상품 목록(`14`).
 *
 * <p><b>서버 컴포넌트로 그린다</b>(`D24`). 공개 데이터라 세션이 필요 없고,
 * 클라이언트에서 부르면 들어올 때마다 빈 뼈대를 먼저 본다.
 *
 * <p><b>쪽 번호는 주소에 있다</b>(`D24` 「상태는 주소에 둔다」). 컴포넌트 상태로 들면
 * 뒤로 가기·새로고침·링크 공유가 다 깨진다.
 */
export default async function ProductsPage({
  searchParams,
}: {
  searchParams: Promise<{ page?: string }>;
}) {
  const requested = await searchParams;
  const page = pageNumberOf(requested.page);

  const result = await apiPublic<ProductPage>(
    `/api/products?page=${page}&size=${PAGE_SIZE}`,
  );

  const lastPage = Math.max(0, Math.ceil(result.total / result.size) - 1);

  return (
    <div className="mx-auto grid w-full max-w-6xl flex-1 content-start gap-8 px-4 py-16">
      <div className="grid gap-2">
        <h1 className="text-3xl font-semibold tracking-tight">상품</h1>
        <p className="text-sm text-text-muted">
          여러 판매자가 등록한 상품입니다. 주문하시면 판매자별로 나뉘어 배송됩니다.
        </p>
      </div>

      {result.items.length > 0 ? (
        <>
          <ul className="grid grid-cols-1 gap-6 md:grid-cols-2 lg:grid-cols-3">
            {result.items.map((item) => (
              // 칸을 늘려서 한 줄의 카드가 같은 높이가 되게 한다. 아래 카드가 그 높이를 받는다.
              <li key={item.productId} className="grid">
                <ProductCard item={item} />
              </li>
            ))}
          </ul>
          <Pager
            page={result.page}
            lastPage={lastPage}
            total={result.total}
            basePath="/products"
            label="상품 목록"
            unit="개"
          />
        </>
      ) : (
        <Empty hasAnyProduct={result.total > 0} />
      )}
    </div>
  );
}

/**
 * 카드 하나.
 *
 * <p><b>카드 전체가 링크다.</b> 이름만 링크로 두면 손가락으로 짚는 대상이 글자 폭만큼 좁아진다.
 * 대신 링크 안에 링크를 넣지 않는다 — 판매자 이름은 글자로만 둔다.
 *
 * <p><b>가격이 카드 아래에 붙는다.</b> 이름이 두 줄인 상품이 섞이면 가격 줄이 카드마다 어긋나고,
 * 그러면 눈으로 훑어 비교할 수가 없다 — `D20` 이 균등 그리드를 고른 이유가 그것이다.
 */
function ProductCard({ item }: { item: ProductItem }) {
  return (
    <Link
      href={`/products/${item.productId}`}
      className="
        group grid grid-rows-[auto_1fr] gap-3 rounded-ui border border-border bg-surface-raised p-3
        transition-[border-color,transform] duration-200
        hover:border-accent-text
        motion-safe:active:translate-y-px
        focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
      "
    >
      {/*
        사진이 상품을 설명하지 않는다. 스키마에 이미지가 없어서 상품번호로 받아 온 자리표시라
        내용이 상품과 무관하다 - 읽어 주면 없는 정보를 있는 것처럼 말하게 된다(`D20`).
        청크 26 이 진짜 사진을 붙일 때 alt 에 상품명이 들어간다.
      */}
      <Image
        src={`https://picsum.photos/seed/${item.productId}/600/450`}
        alt=""
        width={600}
        height={450}
        className="aspect-[4/3] w-full rounded-ui object-cover"
      />

      <div className="grid content-between gap-2">
        <div className="grid gap-1">
          <h2 className="text-sm font-semibold leading-snug group-hover:text-accent-text">
            {item.name}
          </h2>
          <p className="text-xs text-text-muted">{item.sellerName}</p>
        </div>
        <TotalPrice price={item.minPriceInclVat} shippingFee={item.shippingFee} />
      </div>
    </Link>
  );
}

/**
 * 사려면 실제로 내야 하는 돈.
 *
 * <p><b>총액이 큰 글자다</b>(`D2` R24, 전자상거래법 제21조의2 1호). 법이 막는 것은
 * 「총금액 중 일부만 표시해서 유인하는 것」이라, 상품가만 크게 두고 배송비를 작게 두면
 * 규제 대상이 된 바로 그 관행이 된다.
 *
 * <p>내역을 같이 적는다. 총액만 있으면 배송비가 얼마인지 못 보고, 그것도 제13조제2항이
 * 요구하는 표시다.
 *
 * <p><b>무료배송은 그렇게 적는다.</b> 배송비 줄을 통째로 빼면 「배송비가 있는데 안 적었나」와
 * 「없나」가 화면에서 안 갈린다.
 */
function TotalPrice({ price, shippingFee }: { price: number; shippingFee: number }) {
  return (
    <div className="grid gap-0.5">
      <p className="text-sm font-semibold">{priceText(price + shippingFee)}</p>
      <p className="text-xs text-text-muted">
        {shippingFee === 0
          ? `상품 ${priceText(price)} · 무료배송`
          : `상품 ${priceText(price)} + 배송비 ${priceText(shippingFee)}`}
      </p>
    </div>
  );
}

/**
 * 아무것도 못 그릴 때.
 *
 * <p><b>없는 것과 이 쪽에 없는 것을 가른다</b>(`D20` 「빈 상태」).
 * 뒤쪽은 주소를 직접 고쳐 들어온 경우라 <b>돌아갈 길을 준다.</b>
 */
function Empty({ hasAnyProduct }: { hasAnyProduct: boolean }) {
  if (hasAnyProduct) {
    return (
      <div className="grid justify-items-start gap-3 py-12">
        <p className="text-sm text-text-muted">이 쪽에는 상품이 없습니다.</p>
        <Link
          href="/products"
          className="
            text-sm font-semibold text-accent-text underline underline-offset-4
            focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
          "
        >
          첫 쪽으로 가기
        </Link>
      </div>
    );
  }

  return (
    <div className="grid gap-2 py-12">
      <p className="text-sm text-text-muted">아직 등록된 상품이 없습니다.</p>
      <p className="text-sm text-text-muted">
        판매자가 상품을 등록하고 검수를 마치면 이 자리에 표시됩니다.
      </p>
    </div>
  );
}

/** 부가세가 이미 포함된 값이다(`D8`). 화면이 다시 더하지 않는다 */
function priceText(amount: number): string {
  return `${amount.toLocaleString("ko-KR")}원`;
}
