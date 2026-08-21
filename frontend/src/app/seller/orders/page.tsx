import type { Metadata } from "next";
import Link from "next/link";

import { Pager, pageNumberOf } from "@/components/pager";
import { apiSession } from "@/lib/api-session";
import { dateTimeText, priceText } from "@/lib/format";
import { shipmentStatusText } from "@/lib/order-text";

export const metadata: Metadata = { title: "받은 주문 · ProjectShop" };

/** 한 쪽에 몇 개. 서버 기본값과 같게 둔다(`D5` 「목록」) */
const PAGE_SIZE = 20;

type SellerOrderSummary = {
  sellerOrderNumber: string;
  orderNumber: string;
  status: string;
  itemCount: number;
  shippingFee: number;
  /** 이날까지 보내야 한다. 취소된 묶음에는 없다(`V30`) */
  shipDueAt: string | null;
  shipOverdue: boolean;
  createdAt: string;
};

type SellerOrderPage = {
  items: SellerOrderSummary[];
  page: number;
  size: number;
  total: number;
};

/**
 * 받은 주문(`13g`).
 *
 * <p><b>이 화면이 답하는 물음은 「무엇부터 보내나」다.</b> 그래서 발송 기한이 줄마다 있다 —
 * 기한을 넘기면 지연배상금이 연 15% 로 붙고(전자상거래법 시행령 제21조의3, `D2` R21),
 * 상세를 열어야 보이면 목록이 그 물음에 답을 안 하는 것이다.
 *
 * <p><b>판정은 서버가 한다.</b> 늦었는지를 화면이 날짜로 다시 재지 않는다 —
 * 뷰가 `is_ship_overdue` 로 답한다(`V37`). 화면마다 재면 화면마다 답이 갈린다.
 *
 * <p><b>바깥 틀은 `seller/layout.tsx` 가 진다</b>(`13c-1`). 밀도 7 이라 카드가 아니라 줄이고,
 * 컨테이너를 여기서 다시 만들지 않는다.
 *
 * <p><b>여러 셀러에 속한 사람은 거를 수 있다.</b> 조건은 주소에 있고(`D24`),
 * 쪽을 넘겨도 {@link Pager} 가 그것을 들고 간다.
 */
export default async function SellerOrdersPage({
  searchParams,
}: {
  searchParams: Promise<{ page?: string; sellerId?: string }>;
}) {
  const requested = await searchParams;
  const page = pageNumberOf(requested.page);
  const sellerId = requested.sellerId;

  const query = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE) });
  if (sellerId) {
    query.set("sellerId", sellerId);
  }

  const result = await apiSession<SellerOrderPage>(`/api/seller/orders?${query}`);
  const lastPage = Math.max(0, Math.ceil(result.total / result.size) - 1);

  return (
    <>
      <div className="grid gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">받은 주문</h1>
        <p className="text-sm text-text-muted">
          발송 기한이 이른 주문부터 처리해 주세요.
        </p>
      </div>

      {result.items.length > 0 ? (
        <>
          <OrderTable items={result.items} />
          <Pager
            page={result.page}
            lastPage={lastPage}
            total={result.total}
            basePath="/seller/orders"
            label="받은 주문 목록"
            unit="건"
            params={{ sellerId }}
          />
        </>
      ) : (
        <Empty hasAnyOrder={result.total > 0} />
      )}
    </>
  );
}

/**
 * 받은 주문을 줄로 그린다.
 *
 * <p><b>표다.</b> 셀러 화면은 여러 건을 한 화면에서 훑는 자리라 칸이 세로로 맞아야 눈이 훑는다
 * (`D20` 밀도 7). 카드로 그리면 같은 값이 줄마다 다른 x 좌표에 온다.
 *
 * <p>좁은 화면에서는 가로로 민다. <b>칸을 접지 않는다</b> — 접으면 기한과 상태가
 * 다른 줄로 가서 「이 주문이 늦었나」를 한눈에 못 본다.
 */
function OrderTable({ items }: { items: SellerOrderSummary[] }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[44rem] border-collapse text-sm">
        <caption className="sr-only">받은 주문 목록. 주문번호, 상태, 발송 기한, 배송비 순</caption>
        <thead>
          <tr className="border-b border-border text-left text-xs text-text-muted">
            <Th>주문번호</Th>
            <Th>접수</Th>
            <Th>상태</Th>
            <Th>발송 기한</Th>
            <Th align="right">상품</Th>
            <Th align="right">배송비</Th>
          </tr>
        </thead>
        <tbody>
          {items.map((order) => (
            <tr key={order.sellerOrderNumber} className="border-b border-border">
              <Td>
                <Link
                  href={`/seller/orders/${order.sellerOrderNumber}`}
                  className="
                    font-mono text-accent-text underline underline-offset-4
                    focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
                  "
                >
                  {order.sellerOrderNumber}
                </Link>
              </Td>
              <Td muted>{dateTimeText(order.createdAt)}</Td>
              <Td>{shipmentStatusText(order.status)}</Td>
              <Td>
                <ShipDue at={order.shipDueAt} overdue={order.shipOverdue} />
              </Td>
              <Td align="right">{order.itemCount}종</Td>
              <Td align="right">
                <span className="font-mono">{priceText(order.shippingFee)}</span>
              </Td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/**
 * 발송 기한.
 *
 * <p><b>늦은 것을 색으로만 알리지 않는다</b>(`D20`·WCAG 1.4.1). 「지남」이라고 적어서
 * 색을 못 보는 사람에게도 같은 사실이 간다.
 *
 * <p>기한이 없는 줄은 취소된 묶음이다(`V30`). 빈 칸으로 두면 「없나」와 「안 그렸나」가 안 갈린다.
 */
function ShipDue({ at, overdue }: { at: string | null; overdue: boolean }) {
  if (at === null) {
    return <span className="text-text-muted">해당 없음</span>;
  }

  return (
    <span className={overdue ? "font-semibold text-danger-text" : undefined}>
      <span className="font-mono">{dateTimeText(at)}</span>
      {overdue ? " 지남" : null}
    </span>
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
          href="/seller/orders"
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
      <p className="text-sm text-text-muted">아직 받은 주문이 없습니다.</p>
      <p className="text-sm text-text-muted">
        구매자가 결제를 마치면 이 자리에 표시됩니다.
      </p>
    </div>
  );
}

function Th({ children, align }: { children: string; align?: "right" }) {
  return (
    <th scope="col" className={`py-2 pr-3 font-normal ${align === "right" ? "text-right" : ""}`}>
      {children}
    </th>
  );
}

function Td({
  children,
  align,
  muted = false,
}: {
  children: React.ReactNode;
  align?: "right";
  muted?: boolean;
}) {
  return (
    <td
      className={`
        py-2 pr-3 align-top
        ${align === "right" ? "text-right" : ""}
        ${muted ? "text-text-muted" : ""}
      `}
    >
      {children}
    </td>
  );
}
