-- V91 을 되돌린다. 쿠폰 권한이 사라지고 `Q163` 의 입구가 전부 403 이 된다.
--
-- **표와 계산은 그대로 남는다**(`V86`~`V88`). 되돌려도 이미 나간 발급과 이미 적용된
-- 할인은 안 건드리고, 새로 만들거나 받는 길만 닫힌다.
delete from role_permission
 where permission_id in (select permission_id from permission
                          where resource in ('coupon', 'coupon_issue'));
delete from permission where resource in ('coupon', 'coupon_issue');
