import type { Metadata } from "next";
import Link from "next/link";

import { Pager, pageNumberOf } from "@/components/pager";
import { apiSession } from "@/lib/api-session";
import { dateTimeText, priceText } from "@/lib/format";
import { productStatusHint, productStatusText } from "@/lib/product-text";

export const metadata: Metadata = { title: "내 상품 · ProjectShop" };

/** 한 쪽에 몇 개. 서버 기본값과 같게 둔다(`D5` 「목록」) */
const PAGE_SIZE = 20;

type SellerProduct = {
  productId: number;
  sellerId: number;
  name: string;
  status: string;
  commissionBp: number | null;
  minPriceInclVat: number;
  totalStock: number;
  createdAt: string;
};

type SellerProductPage = {
  items: SellerProduct[];
  page: number;
  size: number;
  total: number;
};

/**
 * 내 상품(`13f`).
 *
 * <p><b>이 화면이 답하는 물음은 「무엇이 안 팔리고 있나」다.</b> 그래서 줄마다 상태와 재고가 있다 —
 * 검수 대기와 품절은 둘 다 「안 팔린다」인데 <b>해야 할 일이 정반대</b>고,
 * 상세를 열어야 보이면 목록이 그 물음에 답을 안 하는 것이다.
 *
 * <p><b>바깥 틀은 `seller/layout.tsx` 가 진다</b>(`13c-1`). 밀도 7 이라 카드가 아니라 줄이고,
 * 컨테이너를 여기서 다시 만들지 않는다 — `13g` 의 받은 주문과 같은 표 모양이다.
 *
 * <p><b>등록·수정은 아직 없다.</b> 서버에 만드는 입구가 없어서 화면만 그리면
 * 눌러도 아무 일이 안 나는 버튼이 된다 — 그쪽은 준비 중 자리표시로 둔다(`D20`).
 */
export default async function SellerProductsPage({
  searchParams,
}: {
  searchParams: Promise<{ page?: string; sellerId?: string }>;
}) {
  const requested = await searchParams;
  const page = pageNumberOf(requested.page);
  const sellerId = requested.sellerId;

  const query = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE) });
  if (sellerId) {
    query.set("seller_id", sellerId);
  }

  const result = await apiSession<SellerProductPage>(`/api/seller/products?${query}`);
  const lastPage = Math.max(0, Math.ceil(result.total / result.size) - 1);

  return (
    <>
      <div className="grid gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">내 상품</h1>
        <p className="text-sm text-text-muted">
          검수를 지난 상품만 판매됩니다. 상태에 따라 하실 일이 달라집니다.
        </p>
      </div>

      {result.items.length > 0 ? (
        <>
          <ProductTable items={result.items} />
          <Pager
            page={result.page}
            lastPage={lastPage}
            total={result.total}
            basePath="/seller/products"
            label="내 상품 목록"
            unit="개"
            params={{ sellerId }}
          />
        </>
      ) : (
        <Empty />
      )}
    </>
  );
}

/**
 * 상품을 줄로 그린다.
 *
 * <p><b>표다.</b> 셀러 화면은 여러 건을 한 화면에서 훑는 자리라 칸이 세로로 맞아야 눈이 훑는다
 * (`D20` 밀도 7). 카드로 그리면 같은 값이 줄마다 다른 x 좌표에 온다.
 *
 * <p>좁은 화면에서는 가로로 민다. <b>칸을 접지 않는다</b> — 접으면 상태와 재고가
 * 다른 줄로 가서 「이게 왜 안 팔리나」를 한눈에 못 본다.
 */
function ProductTable({ items }: { items: SellerProduct[] }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[44rem] border-collapse text-sm">
        <caption className="sr-only">내 상품 목록. 이름, 상태, 최저가, 재고, 등록일 순</caption>
        <thead>
          <tr className="border-b border-border text-left text-xs text-text-muted">
            <Th>상품</Th>
            <Th>상태</Th>
            <Th align="right">최저가</Th>
            <Th align="right">재고</Th>
            <Th>등록</Th>
          </tr>
        </thead>
        <tbody>
          {items.map((product) => (
            <tr key={product.productId} className="border-b border-border">
              <Td>{product.name}</Td>
              <Td>
                <Status status={product.status} />
              </Td>
              <Td align="right">
                <span className="font-mono">{priceText(product.minPriceInclVat)}</span>
              </Td>
              <Td align="right">
                <Stock count={product.totalStock} />
              </Td>
              <Td muted>{dateTimeText(product.createdAt)}</Td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/**
 * 상태와 그때 할 일.
 *
 * <p><b>색으로만 가르지 않는다</b>(WCAG 1.4.1). 막힌 것은 글자로도 막혔다고 적는다 —
 * 색만 쓰면 색을 못 가리는 사람에게 「판매 중」과 「판매 차단」이 같은 줄로 보인다.
 */
function Status({ status }: { status: string }) {
  const hint = productStatusHint(status);
  const blocked = status === "blocked";

  return (
    <span className="grid gap-0.5">
      <span className={blocked ? "font-semibold text-danger-text" : undefined}>
        {productStatusText(status)}
      </span>
      {hint ? <span className="text-xs text-text-muted">{hint}</span> : null}
    </span>
  );
}

/** 0 은 숫자로만 두지 않는다. 「품절」이 상태 칸에 있어도 재고 칸이 그것을 반복해야 눈이 멈춘다 */
function Stock({ count }: { count: number }) {
  if (count === 0) {
    return <span className="font-semibold text-danger-text">없음</span>;
  }
  return <span className="font-mono">{count.toLocaleString("ko-KR")}</span>;
}

/**
 * 상품이 하나도 없을 때.
 *
 * <p><b>빈 화면에 「없습니다」만 두지 않는다</b>(`D20`). 무엇을 하면 되는지가 없으면
 * 사용자가 화면이 고장 난 것인지 자기가 할 일이 있는 것인지를 못 가른다.
 */
function Empty() {
  return (
    <p className="rounded-md border border-border px-4 py-8 text-center text-sm text-text-muted">
      아직 등록한 상품이 없습니다.{" "}
      <Link
        href="/seller/products/new"
        className="
          text-accent-text underline underline-offset-4
          focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
        "
      >
        상품 등록
      </Link>
      에서 시작하실 수 있습니다.
    </p>
  );
}

function Th({ children, align }: { children: React.ReactNode; align?: "right" }) {
  return (
    <th scope="col" className={`px-2 py-2 font-medium ${align === "right" ? "text-right" : ""}`}>
      {children}
    </th>
  );
}

function Td({
  children,
  align,
  muted,
}: {
  children: React.ReactNode;
  align?: "right";
  muted?: boolean;
}) {
  return (
    <td
      className={`px-2 py-3 align-top ${align === "right" ? "text-right" : ""} ${
        muted ? "text-text-muted" : ""
      }`}
    >
      {children}
    </td>
  );
}
