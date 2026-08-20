import type { Metadata } from "next";
import Link from "next/link";

import { apiSession } from "@/lib/api-session";
import { dateTimeText } from "@/lib/format";

import { paymentStatusText, priceText } from "./status";

export const metadata: Metadata = { title: "내 주문 · ProjectShop" };

/** 한 쪽에 몇 개. 서버 기본값과 같게 둔다(`D5` 「목록」) */
const PAGE_SIZE = 20;

/** 쪽 번호를 몇 개까지 늘어놓나 */
const PAGE_LINK_WINDOW = 5;

type OrderSummary = {
  orderNumber: string;
  status: string;
  payableAmount: number;
  itemCount: number;
  createdAt: string;
};

type OrderPage = { items: OrderSummary[]; page: number; size: number; total: number };

/**
 * 내 주문 목록(`15-3`).
 *
 * <p><b>거래기록 열람의 입구다</b>(`D2` R6 제3항, 전자상거래법 제6조). 산 사람이 자기 거래를
 * 다시 볼 수 있어야 하고, 그 상세가 상태 이력까지 그린다.
 *
 * <p><b>서버 컴포넌트다</b>(`D24`). 사람마다 다른 데이터라 캐시가 안 걸리고,
 * 클라이언트에서 부르면 들어올 때마다 빈 뼈대를 먼저 본다.
 *
 * <p><b>쪽 번호는 주소에 있다</b>(`D24` 「상태는 주소에 둔다」).
 */
export default async function OrdersPage({
  searchParams,
}: {
  searchParams: Promise<{ page?: string }>;
}) {
  const requested = await searchParams;
  const page = pageNumberOf(requested.page);

  const result = await apiSession<OrderPage>(
    `/api/orders?page=${page}&size=${PAGE_SIZE}`,
  );

  const lastPage = Math.max(0, Math.ceil(result.total / result.size) - 1);

  return (
    <div className="mx-auto grid w-full max-w-4xl flex-1 content-start gap-8 px-4 py-16">
      <div className="grid gap-2">
        <h1 className="text-3xl font-semibold tracking-tight">내 주문</h1>
        <p className="text-sm text-text-muted">
          주문하신 내역과 처리 상태를 확인하실 수 있습니다.
        </p>
      </div>

      {result.items.length > 0 ? (
        <>
          <ul className="grid gap-3">
            {result.items.map((order) => (
              <li key={order.orderNumber}>
                <OrderCard order={order} />
              </li>
            ))}
          </ul>
          <Pager page={result.page} lastPage={lastPage} total={result.total} />
        </>
      ) : (
        <Empty hasAnyOrder={result.total > 0} />
      )}
    </div>
  );
}

/**
 * 주문 하나.
 *
 * <p><b>카드 전체가 링크다.</b> 주문번호만 링크로 두면 짚을 대상이 글자 폭만큼 좁아진다.
 */
function OrderCard({ order }: { order: OrderSummary }) {
  return (
    <Link
      href={`/orders/${order.orderNumber}`}
      className="
        group grid gap-2 rounded-ui border border-border bg-surface-raised p-4
        transition-colors duration-200
        hover:border-accent-text
        focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
      "
    >
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="text-sm font-semibold group-hover:text-accent-text">
          {order.orderNumber}
        </h2>
        <p className="text-xs text-text-muted">{dateTimeText(order.createdAt)}</p>
      </div>

      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <p className="text-sm">
          {paymentStatusText(order.status)}
          <span className="text-text-muted"> · 상품 {order.itemCount}종</span>
        </p>
        <p className="text-sm font-semibold">{priceText(order.payableAmount)}</p>
      </div>
    </Link>
  );
}

/**
 * 아무것도 못 그릴 때.
 *
 * <p><b>없는 것과 이 쪽에 없는 것을 가른다</b>(`D20` 「빈 상태」).
 * 뒤쪽은 주소를 직접 고쳐 들어온 경우라 돌아갈 길을 준다.
 */
function Empty({ hasAnyOrder }: { hasAnyOrder: boolean }) {
  if (hasAnyOrder) {
    return (
      <div className="grid justify-items-start gap-3 py-12">
        <p className="text-sm text-text-muted">이 쪽에는 주문이 없습니다.</p>
        <Link
          href="/orders"
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
    <div className="grid justify-items-start gap-3 py-12">
      <p className="text-sm text-text-muted">주문하신 상품이 없습니다.</p>
      <Link
        href="/products"
        className="
          text-sm font-semibold text-accent-text underline underline-offset-4
          focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
        "
      >
        상품 둘러보기
      </Link>
    </div>
  );
}

/** 쪽 번호. 상품 목록과 같은 규칙이다(`D20` 「목록 넘김」) */
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
    <nav aria-label="주문 목록 쪽 넘기기" className="grid justify-items-center gap-3">
      <ul className="flex flex-wrap items-center justify-center gap-1">
        <li>
          <PagerLink page={page - 1} disabled={page === 0} label="이전 쪽">
            이전
          </PagerLink>
        </li>

        {numbers.map((number) => (
          <li key={number}>
            <PagerLink page={number} current={number === page} label={`${number + 1}쪽`}>
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

      <p className="text-xs text-text-muted">
        전체 {total.toLocaleString("ko-KR")}건 중 {page + 1} / {lastPage + 1}쪽
      </p>
    </nav>
  );
}

/** 갈 곳이 없으면 링크를 안 그린다(`D20`) */
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
      href={page === 0 ? "/orders" : `/orders?page=${page}`}
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

/** 주소에서 온 쪽 번호. <b>믿지 않는다</b> — 이상한 값은 첫 쪽으로 본다 */
function pageNumberOf(raw: string | undefined): number {
  const parsed = Number(raw);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : 0;
}
