-- 반품의 입고와 판정을 동작으로 가른다.
--
-- V63 이 반품 표를 세우고 43a-1 이 접수 행을 열었는데 판정하는 코드가 없어서
-- 묶음이 return_requested 에서 안 움직였다. returned 로 가려면 승인된 반품이 있어야 하고
-- (seller_order_return_status_check), 그 승인을 만드는 자리가 없었다.
--
-- 동작을 셋으로 가르는 이유는 V20 과 같다 — 상태 축의 단위가 resource:action 이고
-- StatusPolicy.allowedStatuses(resource, action) 에 역할이 없다.
--
--   update_status 가 return_requested 를 들고 있으면
--     → 셀러가 DELIVER 로 return_requested → delivered 를 민다. 그것이 반품 거절이고,
--       D7 이 「관리자만」이라고 적어 둔 전이다. 문서는 강제 지점 5순위라 아무것도 안 막았다.
--       셀러가 배송완료를 되돌릴 수 있으면 청약철회 기산점을 조작할 수 있다.
--   승인까지 셀러에게 주면
--     → 제17조제5항이 훼손 책임의 입증을 통신판매업자에게 지운 것과 어긋난다(D2 R37).
--       셀러의 소견이 곧 결론이 되면 입증책임이 우리에게 있다는 사실이 데이터에서 사라진다.
--
-- 그래서 셀러는 입고까지 하고 멈추고, 판정 둘은 관리자만 한다(43a-2, 사용자 선택).
-- 그 결정으로 state-machines.md 의 전이 주체표를 같이 고쳤다 — 그전에는 승인이 「셀러」였다.

insert into permission (resource, action, description) values
    ('order', 'receive_return', '반품 물건을 입고 처리한다'),
    ('order', 'approve_return', '반품을 승인한다'),
    ('order', 'reject_return',  '반품을 거절한다');


-- 판매자(대표). 입고만 받는다.
--
-- 물건이 실제로 도착했는지는 받아 본 셀러가 안다. 입고 시각이 환급 기산점이라
-- (제18조제2항 1호 「재화등을 반환받은 날」) 관측한 사람이 적는 것이 맞다.
--
-- 판정 둘은 안 준다. 위 주석의 R37 이 그 이유다.
insert into role_permission (role_id, permission_id, scope, effect)
select r.role_id, p.permission_id, 'seller', 'allow'
  from role r
  join permission p on p.resource = 'order' and p.action = 'receive_return'
 where r.code = 'seller_owner';


-- 고객에게는 셋 다 안 준다.
--
-- 접수(request_return)까지가 고객의 몫이고, 무르는 것은 권리가 아니다 —
-- 제17조제4항이 청약철회를 발신주의로 정했고 민법 제543조제2항이 그 의사표시의 철회를 막는다.
-- 근거는 state-machines.md 「접수 취소가 없다」에 적었다.


-- 관리자. V3·V5 의 일괄 부여는 그 시점의 permission 만 훑었으므로 새 권한은 여기서 넣는다.
insert into role_permission (role_id, permission_id, scope, effect)
select r.role_id, p.permission_id, 'all', 'allow'
  from role r
  join permission p on p.resource = 'order'
 where r.code = 'admin'
   and p.action in ('receive_return', 'approve_return', 'reject_return');


-- 감사자. 읽기가 아니면 막는다.
insert into role_permission (role_id, permission_id, scope, effect)
select r.role_id, p.permission_id, 'all', 'deny'
  from role r
  join permission p on p.resource = 'order'
 where r.code = 'auditor'
   and p.action in ('receive_return', 'approve_return', 'reject_return');


-- 감사자 거부가 실제로 걸렸는지 확인한다. 빠뜨리면 아무 일도 안 일어나서 안 드러난다.
do $$
declare missing int;
begin
    select count(*) into missing
      from permission p
     where p.resource = 'order'
       and p.action in ('receive_return', 'approve_return', 'reject_return')
       and not exists (
           select 1
             from role_permission rp
             join role r on r.role_id = rp.role_id
            where rp.permission_id = p.permission_id
              and r.code = 'auditor' and rp.effect = 'deny');

    if missing > 0 then
        raise exception '감사자 거부가 안 걸린 반품 동작이 % 개 있다', missing;
    end if;
end $$;


-- 판정 둘이 셀러에게 안 갔는지 확인한다.
--
-- 이 청크의 요지가 「셀러가 판정을 못 한다」라서 그것을 데이터로 못박는다.
-- 뒤 마이그레이션이 셀러에게 order 권한을 일괄로 주면 여기서 터진다.
do $$
declare leaked int;
begin
    select count(*) into leaked
      from role_permission rp
      join role r on r.role_id = rp.role_id
      join permission p on p.permission_id = rp.permission_id
     where r.code = 'seller_owner'
       and rp.effect = 'allow'
       and p.resource = 'order'
       and p.action in ('approve_return', 'reject_return');

    if leaked > 0 then
        raise exception '셀러에게 반품 판정 권한이 % 개 갔다 (D2 R37)', leaked;
    end if;
end $$;


-- 반품으로 돌아온 재고에 자기 사유를 준다.
--
-- V41 이 목록을 넷으로 닫았는데(initial·order_placed·order_cancelled·adjustment)
-- 반품 승인의 재고 복구를 order_cancelled 로 적으면 **사실이 아닌 값**이 들어간다 —
-- 취소는 물건이 안 나간 것이고 반품은 나갔다 돌아온 것이라, 사유별 집계에서 둘이 섞이면
-- 「셀러가 왜 재고를 다시 갖게 됐나」에 답이 안 나온다.
--
-- 목록을 닫아 둔 값을 하나 늘리는 것이지 여는 것이 아니다(D23 「열거값」).
alter table sku_stock_movement
    drop constraint sku_stock_movement_reason_check;

alter table sku_stock_movement
    add constraint sku_stock_movement_reason_check
        check (reason in ('initial', 'order_placed', 'order_cancelled',
                          'return_restocked', 'adjustment'));
