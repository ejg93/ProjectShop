-- 주문 상태를 옮기는 동작을 넷으로 가른다.
--
-- V3 는 order:update_status 하나로 여섯 전이를 다 맡겼다. 그때는 부르는 곳이 없어서 드러나지 않았고,
-- 청크 11c-3 이 HTTP 경로를 열면서 걸렸다.
--
-- 가르는 이유는 상태 축의 단위가 resource:action 이라서다(D6).
-- StatusPolicy.allowedStatuses(resource, action) 시그니처에 역할이 없다 —
-- 고객과 셀러가 같은 action 을 쓰면 두 쪽의 허용 상태가 한 집합이 된다.
--
--   delivered 를 그 집합에 넣으면  → 셀러가 고객 대신 구매확정을 누른다.
--                                    확정은 정산 대상이 되는 사건이라 셀러가 자기 정산을 당긴다
--                                    (D7 「배송 후에는 셀러도 못 고친다」).
--   고객에게 update_status 를 주면 → 고객이 preparing → shipping → delivered 를 혼자 민다.
--                                    셀러가 물건을 안 보낸 주문이 확정까지 간다.
--                                    전이표는 화살표 순서만 알고 주체를 모르므로 못 막는다.
--
-- 동작을 가르면 각 동작이 자기 상태 목록을 갖는다. 그 표가 OrderStatusPolicy 다.
--
-- 셋 다 부르는 곳이 이미 있다(CLAUDE.md 「확장성을 재는 방법」) —
-- confirm 은 정산(17~21)이 "확정된 것만" 으로 걸고, request_return 은 반품 축(43·44)과
-- 환불 승인(12a)이, cancel 은 부분 취소·환불(12)이 부른다.

insert into permission (resource, action, description) values
    ('order', 'cancel',         '배송 전 주문을 취소한다'),
    ('order', 'confirm',        '배송받은 주문을 구매확정한다'),
    ('order', 'request_return', '배송받은 주문의 반품을 접수한다');


-- 고객. 자기 주문만 옮긴다(D7 「누가 옮기나」).
--
-- update_status 는 안 준다. 셀러가 하는 발송·배송완료·반품완료가 거기 걸려 있다.
insert into role_permission (role_id, permission_id, scope, effect)
select r.role_id, p.permission_id, 'own', 'allow'
  from role r
  join permission p on p.resource = 'order'
 where r.code = 'customer'
   and p.action in ('cancel', 'confirm', 'request_return');


-- 판매자(대표). 취소만 받는다.
--
-- D7 이 preparing → cancelled 를 "고객 또는 셀러" 로 정했다. 품절·오등록으로 못 보내는 건을
-- 셀러가 닫는 자리다. confirm·request_return 은 안 준다 — 고객이 일으키는 사건이다.
insert into role_permission (role_id, permission_id, scope, effect)
select r.role_id, p.permission_id, 'seller', 'allow'
  from role r
  join permission p on p.resource = 'order' and p.action = 'cancel'
 where r.code = 'seller_owner';


-- 관리자. V3 의 admin 부여는 그 시점의 permission 만 훑었으므로 새 권한은 여기서 넣는다.
insert into role_permission (role_id, permission_id, scope, effect)
select r.role_id, p.permission_id, 'all', 'allow'
  from role r
  join permission p on p.resource = 'order'
 where r.code = 'admin'
   and p.action in ('cancel', 'confirm', 'request_return');


-- 감사자. 읽기가 아니면 막는다.
--
-- V5 가 같은 규칙을 넣었지만 그 insert 도 그 시점의 permission 만 훑었다.
-- V12 의 주석이 "새 권한을 넣는 마이그레이션이 감사자 deny 도 같이 넣어야 한다" 고 남긴 자리다.
insert into role_permission (role_id, permission_id, scope, effect)
select r.role_id, p.permission_id, 'all', 'deny'
  from role r
  join permission p on p.resource = 'order'
 where r.code = 'auditor'
   and p.action in ('cancel', 'confirm', 'request_return');


-- 감사자 거부가 실제로 걸렸는지 확인한다.
--
-- 이 검사를 두는 이유는 빠뜨렸을 때 아무 일도 안 일어나기 때문이다 —
-- 감사자가 주문 상태를 옮길 수 있게 되는데 오류도 로그도 안 남는다.
do $$
declare missing int;
begin
    select count(*) into missing
      from permission p
     where p.resource = 'order'
       and p.action in ('cancel', 'confirm', 'request_return')
       and not exists (
           select 1
             from role_permission rp
             join role r on r.role_id = rp.role_id
            where rp.permission_id = p.permission_id
              and r.code = 'auditor' and rp.effect = 'deny');

    if missing > 0 then
        raise exception '감사자 거부가 안 걸린 주문 동작이 % 개 있다', missing;
    end if;
end $$;


-- 여기 안 넣은 것 둘. 빠뜨린 것과 구분되게 근거를 남긴다(D23 「안 넣은 것도 근거를 남긴다」).
--
-- 1. seller_owner 의 confirm 거부(own).
--    V5 는 update_status 에 D/own 을 걸어 "자기가 만든 주문은 자기가 못 옮긴다" 를 세웠다.
--    confirm 에 같은 것을 걸면 셀러 대표가 남의 가게에서 산 물건도 확정을 못 한다 —
--    스코프가 own 이라 상대 셀러를 안 가리기 때문이다. 지금 막아서 얻는 것은
--    "자기 셀러 물건을 자기가 사서 확정을 8일 당기는 것" 을 막는 것뿐인데 정산이 아직 없다(17~21).
--    정산이 서면 그 청크가 이 자리를 다시 본다.
--
-- 2. order:force_status.
--    관리자가 전이표 밖으로 옮기는 동작이다. OrderStatusPolicy 가 "표에 예외를 파지 말고
--    동작을 새로 만든다" 고 적어 뒀는데, 지금은 그 경로를 부르는 화면이 없다.
--    청크 16c 가 만든다.
