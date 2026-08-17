import type { Metadata } from "next";
import Image from "next/image";
import Link from "next/link";

import { apiPublic } from "@/lib/api";

export const metadata: Metadata = { title: "상품 · ProjectShop" };

/** 한 쪽에 몇 개. 서버 기본값과 같게 둔다(`D5` 「목록」) */
const PAGE_SIZE = 20;

/** 페이지 번호를 몇 개까지 늘어놓나. 넘으면 앞뒤로만 움직인다 */
const PAGE_LINK_WINDOW = 5;

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
          <Pager page={result.page} lastPage={lastPage} total={result.total} />
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
        <p className="text-sm font-semibold">{priceText(item.minPriceInclVat)}</p>
      </div>
    </Link>
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

/**
 * 쪽 번호. <b>「더 보기」가 아니라 번호다</b>(`D20`) — 상세에 들어갔다 돌아와도 보던 쪽에 남는다.
 *
 * <p>번호를 다 늘어놓지 않는다. 상품이 늘면 줄바꿈이 화면을 밀어낸다.
 */
function Pager({
  page,
  lastPage,
  total,
}: {
  page: number;
  lastPage: number;
  total: number;
}) {
  if (lastPage === 0) {
    return null;
  }

  const half = Math.floor(PAGE_LINK_WINDOW / 2);
  const start = Math.max(0, Math.min(page - half, lastPage - PAGE_LINK_WINDOW + 1));
  const end = Math.min(lastPage, start + PAGE_LINK_WINDOW - 1);
  const numbers = Array.from({ length: end - start + 1 }, (_, index) => start + index);

  return (
    <nav aria-label="상품 목록 쪽 넘기기" className="grid justify-items-center gap-3">
      <ul className="flex flex-wrap items-center justify-center gap-1">
        <li>
          <PagerLink page={page - 1} disabled={page === 0} label="이전 쪽">
            이전
          </PagerLink>
        </li>

        {numbers.map((number) => (
          <li key={number}>
            <PagerLink
              page={number}
              current={number === page}
              label={`${number + 1}쪽`}
            >
              {number + 1}
            </PagerLink>
          </li>
        ))}

        <li>
          <PagerLink page={page + 1} disabled={page === lastPage} label="다음 쪽">
            다음
          </PagerLink>
        </li>
      </ul>

      {/* 지금 어디인지를 글로도 준다. 번호의 강조만으로 알리면 색에 기대는 것이 된다(`D20`) */}
      <p className="text-xs text-text-muted">
        전체 {total.toLocaleString("ko-KR")}개 중 {page + 1} / {lastPage + 1}쪽
      </p>
    </nav>
  );
}

/**
 * 쪽 하나로 가는 링크.
 *
 * <p><b>갈 곳이 없으면 링크를 안 그린다.</b> 비활성 링크로 두면 보조기술이 읽고
 * 만질 수 없다고 말한다 — 안 그리면 없는 것이다(`D20` 「색만으로 알리지 않는다」).
 */
function PagerLink({
  page,
  label,
  children,
  current = false,
  disabled = false,
}: {
  page: number;
  label: string;
  children: React.ReactNode;
  current?: boolean;
  disabled?: boolean;
}) {
  const shape =
    "grid h-9 min-w-9 place-items-center rounded-ui px-3 text-sm transition-colors duration-200";

  if (disabled) {
    return <span className={`${shape} text-text-muted opacity-50`}>{children}</span>;
  }

  return (
    <Link
      href={page === 0 ? "/products" : `/products?page=${page}`}
      aria-label={label}
      aria-current={current ? "page" : undefined}
      className={`
        ${shape}
        focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
        ${
          current
            ? "bg-accent font-semibold text-accent-on"
            : "border border-border hover:border-accent-text"
        }
      `}
    >
      {children}
    </Link>
  );
}

/**
 * 주소에서 온 쪽 번호. <b>믿지 않는다</b> — 사람이 직접 고칠 수 있는 값이다.
 *
 * <p>이상한 값에 오류를 내지 않고 첫 쪽으로 본다. 링크를 잘못 받은 사람에게
 * 오류 화면을 주는 것보다 목록을 주는 편이 낫다.
 */
function pageNumberOf(raw: string | undefined): number {
  const parsed = Number(raw);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : 0;
}

/** 부가세가 이미 포함된 값이다(`D8`). 화면이 다시 더하지 않는다 */
function priceText(amount: number): string {
  return `${amount.toLocaleString("ko-KR")}원`;
}
