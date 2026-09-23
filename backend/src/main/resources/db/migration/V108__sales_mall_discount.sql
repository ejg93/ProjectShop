-- 매출 통계가 몰이 문 할인을 따로 센다(`Q197`, 2026-09-23 사용자 선택 「둘 다 센다」).
--
-- **축이 둘이었는데 칸이 하나였다.** `paid_amount` 는 고객이 낸 돈(할인 뒤)이고, 정산서는 몰이 문 쿠폰이면
-- 셀러에게 **정가**를 준다(`business-model.md`, `Q168`). 그래서 몰 쿠폰 주문에서 매출 화면이 정산서보다
-- 할인액만큼 작았고 둘을 맞출 줄이 없었다(마무리 45차 독립 리뷰).
--
-- **셀러 매출 = 고객 결제액 + 몰이 문 할인**이다. 환불도 같은 모양이라 칸이 둘이다 — 환불된 몰 할인은
-- `refund_item.discount_refund` 중 몰이 문 쿠폰의 몫이다. 셀러가 문 할인은 이미 `paid_amount` 에서 빠져 있어서
-- 따로 안 센다: 셀러 몫에서 빠진 돈이 곧 셀러가 문 것이다.
alter table seller_daily_sales
    add column mall_discount_amount bigint not null default 0,
    add column refunded_mall_discount_amount bigint not null default 0;

alter table seller_daily_sales add constraint seller_daily_sales_mall_discount_check
    check (mall_discount_amount >= 0 and refunded_mall_discount_amount >= 0);

comment on column seller_daily_sales.mall_discount_amount is
    '그날 결제된 줄의 할인 중 몰이 문 몫. 셀러 매출은 paid_amount 에 이것을 더한 것이다(Q197)';
comment on column seller_daily_sales.refunded_mall_discount_amount is
    '그날 승인된 환불이 되돌린 할인 중 몰이 문 몫. 셀러 매출의 환불 축이다(Q197)';

-- 이미 센 날을 원장에서 채운다. 기본값 0 으로 두면 지난 날의 셀러 매출이 몰 쿠폰만큼 작게 남는다 —
-- 배치는 어제만 다시 세서 스스로 안 고친다. 식은 `DailySalesService.TALLY` 와 같은 조인이다.
update seller_daily_sales s
   set mall_discount_amount = coalesce((
           select sum(oi.discount_amount)
             from payment p
             join seller_order so on so.order_id = p.order_id
             join order_item oi   on oi.seller_order_id = so.seller_order_id
             join coupon_issue ci on ci.used_order_id = so.order_id
             join coupon c        on c.coupon_id = ci.coupon_id
            where p.status = 'approved'
              and c.bearer = 'mall'
              and so.seller_id = s.seller_id
              and (p.created_at at time zone 'Asia/Seoul')::date = s.sales_date), 0),
       refunded_mall_discount_amount = coalesce((
           select sum(ri.discount_refund)
             from refund r
             join seller_order so on so.seller_order_id = r.seller_order_id
             join refund_item ri  on ri.refund_id = r.refund_id
             join coupon_issue ci on ci.used_order_id = so.order_id
             join coupon c        on c.coupon_id = ci.coupon_id
            where r.status = 'approved'
              and c.bearer = 'mall'
              and so.seller_id = s.seller_id
              and (r.decided_at at time zone 'Asia/Seoul')::date = s.sales_date), 0);
