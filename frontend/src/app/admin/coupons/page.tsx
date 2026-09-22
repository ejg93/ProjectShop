import type { Metadata } from "next";

import { apiSession } from "@/lib/api-session";
import { dateTimeText, priceText } from "@/lib/format";

import { NewCouponForm } from "./new-coupon-form";

export const metadata: Metadata = { title: "쿠폰 관리 · ProjectShop" };

/**
 * 쿠폰 정의 한 줄. 서버의 `CouponQuery.Definition` 과 짝이다.
 *
 * <p><b>여기에는 코드가 있다.</b> 그것을 알려 주는 것이 이 목록의 일이고, 그래서 이 화면이
 * 관리자만 지나는 자리다 — 코드를 아는 것이 곧 그 쿠폰을 받을 수 있다는 뜻이다(`V91`).
 */
type CouponDefinition = {
  couponId: number;
  code: string;
  name: string;
  /** `amount` 면 원, `percent` 면 bp(1000 = 10.00%) */
  discountKind: string;
  discountValue: number;
  maxDiscountAmount: number | null;
  minOrderAmount: number;
  /** `mall` 이면 우리가, `seller` 면 그 셀러가 문다(`51`) */
  bearer: string;
  sellerId: number | null;
  validDays: number;
  issueStartAt: string;
  /** 발급 창의 끝. 없으면 계속 열려 있다 */
  issueEndAt: string | null;
};

type CouponDefinitionPage = {
  items: CouponDefinition[];
  page: number;
  size: number;
  total: number;
};

/**
 * 쿠폰 관리(`Q163`).
 *
 * <p><b>`49`·`50`·`51` 이 표·계산·정산을 세우고 이 자리를 안 만들었다.</b> 쿠폰이
 * {@code psql} 로만 생기던 것이 여기서 닫힌다.
 *
 * <p><b>부담 주체를 목록에 그린다.</b> 정산이 그 값으로 갈려서(`51`), 잘못 고르면
 * 셀러가 자기가 안 낸 할인을 문다 — 만든 뒤에 눈으로 대조할 자리가 필요하다.
 *
 * <p><b>판정은 서버가 한다.</b> 여기까지 와도 권한이 없으면 응답이 안 온다 —
 * 셸이 링크를 가리는 것은 방어가 아니다(`D20`).
 *
 * <p>바깥 틀은 {@code admin/layout.tsx} 가 진다.
 */
export default async function AdminCouponsPage() {
  const page = await apiSession<CouponDefinitionPage>("/api/coupons?page=0&size=50");

  return (
    <>
      <div className="grid gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">쿠폰 관리</h1>
        <p className="text-sm text-text-muted">
          만든 쿠폰은 코드를 알려 준 사람만 받습니다. 목록을 공개하지 않습니다.
        </p>
      </div>

      <NewCouponForm />

      <section aria-labelledby="coupon-list-heading" className="grid gap-3">
        <h2 id="coupon-list-heading" className="text-lg font-semibold">
          만든 쿠폰 {page.total}개
        </h2>

        {page.items.length > 0 ? (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="border-b border-border text-xs text-text-muted">
                <tr>
                  <th scope="col" className="py-2 pr-4 font-medium">
                    코드
                  </th>
                  <th scope="col" className="py-2 pr-4 font-medium">
                    이름
                  </th>
                  <th scope="col" className="py-2 pr-4 font-medium">
                    할인
                  </th>
                  <th scope="col" className="py-2 pr-4 font-medium">
                    최소 주문
                  </th>
                  <th scope="col" className="py-2 pr-4 font-medium">
                    부담
                  </th>
                  <th scope="col" className="py-2 pr-4 font-medium">
                    유효
                  </th>
                  <th scope="col" className="py-2 font-medium">
                    발급 기간
                  </th>
                </tr>
              </thead>
              <tbody>
                {page.items.map((coupon) => (
                  <tr key={coupon.couponId} className="border-b border-border">
                    <td className="py-2 pr-4 font-mono text-xs">{coupon.code}</td>
                    <td className="py-2 pr-4">{coupon.name}</td>
                    <td className="py-2 pr-4">{discountText(coupon)}</td>
                    <td className="py-2 pr-4">
                      {coupon.minOrderAmount > 0 ? priceText(coupon.minOrderAmount) : "없음"}
                    </td>
                    <td className="py-2 pr-4">
                      {coupon.bearer === "seller" ? `셀러 ${coupon.sellerId}` : "몰"}
                    </td>
                    <td className="py-2 pr-4">{coupon.validDays}일</td>
                    <td className="py-2 text-xs text-text-muted">
                      {dateTimeText(coupon.issueStartAt)}
                      {coupon.issueEndAt ? ` ~ ${dateTimeText(coupon.issueEndAt)}` : " ~"}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <p className="text-sm text-text-muted">아직 만든 쿠폰이 없습니다.</p>
        )}
      </section>
    </>
  );
}

/** 정률은 bp 라 100으로 나눈다. 상한이 있으면 같이 적는다 */
function discountText(coupon: CouponDefinition): string {
  const base =
    coupon.discountKind === "percent"
      ? `${coupon.discountValue / 100}%`
      : priceText(coupon.discountValue);

  return coupon.maxDiscountAmount !== null
    ? `${base} (최대 ${priceText(coupon.maxDiscountAmount)})`
    : base;
}
