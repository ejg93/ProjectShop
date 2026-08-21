import type { Metadata } from "next";
import Link from "next/link";

import { apiSession } from "@/lib/api-session";
import { priceText } from "@/lib/format";

import { CartLine, type CartItem } from "./cart-line";

export const metadata: Metadata = { title: "장바구니 · ProjectShop" };

/** @param total 살 수 있는 것만 더한 값이다. 품절된 줄은 안 들어간다 */
type Cart = { items: CartItem[]; total: number };

/**
 * 장바구니(`15-1`).
 *
 * <p><b>서버 컴포넌트로 그린다</b>(`D24`). 사람마다 다른 데이터라 세션을 손으로 실어야 하고,
 * 그 운반은 {@link apiSession} 한 군데에 갇혀 있다.
 *
 * <p><b>로그인을 요구하지 않는다.</b> 비로그인도 담는 자원이라(청크 9) 쿠키 토큰으로 주인을 가른다.
 *
 * <p><b>셀러로 묶어서 그린다.</b> 주문이 셀러별로 나뉘어 배송되고 배송비도 그 단위로 붙어서
 * (`D3`), 담는 자리에서 묶임이 안 보이면 주문서에서 처음 알게 된다.
 */
export default async function CartPage() {
  const cart = await apiSession<Cart>("/api/cart");

  if (cart.items.length === 0) {
    return <Empty />;
  }

  const bySeller = groupBySeller(cart.items);
  const unavailable = cart.items.filter((item) => !item.available);

  return (
    <div className="mx-auto grid w-full max-w-4xl flex-1 content-start gap-8 px-4 py-16">
      <div className="grid gap-2">
        <h1 className="text-3xl font-semibold tracking-tight">장바구니</h1>
        <p className="text-sm text-text-muted">
          판매자별로 나뉘어 배송되며, 배송비도 판매자마다 따로 붙습니다.
        </p>
      </div>

      <div className="grid gap-6">
        {bySeller.map((group) => (
          <section
            key={group.sellerId}
            aria-labelledby={`seller-${group.sellerId}`}
            className="grid gap-3 rounded-ui border border-border bg-surface-raised p-4"
          >
            <h2 id={`seller-${group.sellerId}`} className="text-sm font-semibold">
              {group.sellerName}
            </h2>

            <ul className="grid gap-3">
              {group.items.map((item) => (
                <li key={item.cartItemId}>
                  <CartLine item={item} />
                </li>
              ))}
            </ul>

            {/*
              묶음마다 한 번 붙는다. 같은 셀러 것을 여럿 담아도 배송비는 하나라
              줄이 아니라 묶음 바닥에 적는다.
            */}
            <p className="flex justify-between border-t border-border pt-2 text-sm text-text-muted">
              <span>배송비</span>
              <span>{group.shippingFee === 0 ? "무료" : priceText(group.shippingFee)}</span>
            </p>
          </section>
        ))}
      </div>

      <Summary
        total={cart.total}
        shippingTotal={shippingTotalOf(bySeller)}
        blocked={unavailable.length}
      />
    </div>
  );
}

/**
 * 배송비 합.
 *
 * <p><b>살 수 없는 것만 담긴 묶음은 안 센다.</b> 그 묶음은 주문에 안 들어가서 배송비도 안 붙는데,
 * 세면 합계가 실제로 낼 돈보다 커진다.
 */
function shippingTotalOf(groups: SellerGroup[]): number {
  return groups
    .filter((group) => group.items.some((item) => item.available))
    .reduce((sum, group) => sum + group.shippingFee, 0);
}

/**
 * 합계와 다음 걸음.
 *
 * <p><b>품절이 섞여 있으면 주문으로 못 보낸다.</b> 서버가 통째로 거절하기 때문이다
 * (`OrderService` — 조용히 빼면 사려던 것과 산 것이 달라진다). 버튼을 눌러야 알게 되면
 * 사용자는 무엇을 고쳐야 하는지 모른 채 실패만 본다.
 *
 * <p><b>배송비를 여기서 적는다.</b> 예전에는 「배송지를 정해야 나오는 값」이라고 미뤄 뒀는데
 * 그것이 사실과 달랐다 — 배송비는 셀러가 정한 값이라({@code seller.default_shipping_fee})
 * 배송지와 무관하다. 주문서에 가서야 총액이 나오면 <b>결제 직전에 금액이 늘어나는 모양</b>이고,
 * 그게 전자상거래법 제21조의2 1호가 막는 것이다(`D2` R24).
 */
function Summary({
  total,
  shippingTotal,
  blocked,
}: {
  total: number;
  shippingTotal: number;
  blocked: number;
}) {
  return (
    <div className="grid justify-items-end gap-3 border-t border-border pt-6">
      {/* 총액이 큰 글자다. 상품 금액만 크게 두면 배송비가 덤처럼 읽힌다(`D2` R24) */}
      <p className="text-sm text-text-muted">
        결제하실 금액{" "}
        <span className="text-lg font-semibold text-text">{priceText(total + shippingTotal)}</span>
      </p>
      <p className="text-xs text-text-muted">
        상품 {priceText(total)}
        {shippingTotal === 0 ? " · 무료배송" : ` + 배송비 ${priceText(shippingTotal)}`}
      </p>

      {blocked > 0 ? (
        <p role="alert" className="text-sm text-danger-text">
          지금 구매할 수 없는 상품이 {blocked}개 있습니다. 빼신 뒤에 주문해 주세요.
        </p>
      ) : (
        <Link
          href="/checkout"
          className="
            rounded-ui bg-accent px-5 py-2.5 text-sm font-semibold text-accent-on
            transition-[background-color,transform] duration-200
            hover:bg-accent-hover
            motion-safe:active:translate-y-px
            focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
          "
        >
          주문서로 이동
        </Link>
      )}
    </div>
  );
}

/**
 * 담긴 것이 없을 때.
 *
 * <p><b>무엇을 하면 채워지는지 적고 그 길을 준다</b>(`D20` 「빈 상태」).
 * 여기는 필터가 없어서 「조건에 맞는 게 없음」이 성립하지 않는다 — 비어 있으면 안 담은 것이다.
 */
function Empty() {
  return (
    <div className="mx-auto grid w-full max-w-4xl flex-1 content-center justify-items-start gap-3 px-4 py-16">
      <h1 className="text-2xl font-semibold tracking-tight">장바구니</h1>
      <p className="text-sm text-text-muted">담아 두신 상품이 없습니다.</p>
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

/**
 * 셀러로 묶는다. <b>담은 순서를 안에서 지킨다</b> — 서버가 담은 시각 순으로 내려주므로
 * 다시 정렬하지 않는다. 묶음의 순서도 그 안에서 처음 나온 순서다.
 */
/** 배송비는 셀러가 정한 값이라 그 셀러의 어느 줄에서 읽어도 같다 */
type SellerGroup = {
  sellerId: number;
  sellerName: string;
  shippingFee: number;
  items: CartItem[];
};

function groupBySeller(items: CartItem[]): SellerGroup[] {
  const groups = new Map<number, SellerGroup>();

  for (const item of items) {
    const group = groups.get(item.sellerId);
    if (group) {
      group.items.push(item);
    } else {
      groups.set(item.sellerId, {
        sellerId: item.sellerId,
        sellerName: item.sellerName,
        shippingFee: item.shippingFee,
        items: [item],
      });
    }
  }
  return [...groups.values()];
}
