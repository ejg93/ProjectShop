-- 환불 요청과 승인을 다른 권한으로 가른다.
--
-- V3 는 payment:refund 하나로 「승인된 결제를 취소하고 금액을 돌려준다」를 맡겼다.
-- 그때는 부르는 곳이 없어서 드러나지 않았고, 청크 12a-2 가 HTTP 경로를 열면서 걸렸다.
-- V20 이 order:update_status 를 넷으로 가른 것과 같은 자리다.
--
-- 가르는 이유는 요청과 승인의 주체가 다르기 때문이다.
-- 12a-1 이 「자기가 낸 요청은 자기가 승인 못 한다」를 refund_self_approval_check 로 내렸는데,
-- 그 제약은 자기 것만 막는다. 요청하라고 고객에게 payment:refund 를 주면
-- 고객이 남의 환불을 승인한다 — 제약이 안 걸리는 조합이다.
--
--
-- 승인을 관리자에게만 두는 근거는 법이다(D2 R5 「우리가 환급 의무자다」).
--
--   제18조제2항 괄호   통신판매업자에 「소비자로부터 재화등의 대금을 받은 자」를 포함한다.
--                      결제가 shop_order 단위고 우리가 PG 가맹점이라 우리가 그 자다.
--   제20조의2제3항     중개자라고 고지해도 제17조·제18조 책임을 면하지 못한다.
--   제18조제11항       대금을 받은 자와 계약 당사자가 다르면 연대책임.
--
-- 셀러에게 승인을 넘기면 우리 법적 의무의 이행 여부를 남이 정하게 된다.
-- 나중에 「셀러가 소액은 스스로 승인」을 열더라도 그것은 이 권한에 seller 스코프 한 줄을
-- 더하는 일이고, 그때도 기한 책임은 우리에게 남는다.

insert into permission (resource, action, description) values
    ('payment', 'request_refund', '결제한 대금의 환불을 요청한다');


-- 고객. 자기 주문만 요청한다.
--
-- payment:refund 는 안 준다. 요청은 내되 승인은 못 한다는 것이 이 청크의 전부다.
insert into role_permission (role_id, permission_id, scope, effect)
select r.role_id, p.permission_id, 'own', 'allow'
  from role r
  join permission p on p.resource = 'payment' and p.action = 'request_refund'
 where r.code = 'customer';


-- 판매자(대표). 자기 셀러 묶음만 요청한다.
--
-- 품절·오등록으로 취소한 건의 환불을 셀러가 시작하는 자리다.
-- order:cancel 을 이미 seller 스코프로 갖고 있으므로(V20) 취소는 하는데
-- 환불 요청은 못 내는 상태를 안 만든다.
insert into role_permission (role_id, permission_id, scope, effect)
select r.role_id, p.permission_id, 'seller', 'allow'
  from role r
  join permission p on p.resource = 'payment' and p.action = 'request_refund'
 where r.code = 'seller_owner';


-- 관리자. V3 의 admin 부여는 그 시점의 permission 만 훑었으므로 새 권한은 여기서 넣는다.
insert into role_permission (role_id, permission_id, scope, effect)
select r.role_id, p.permission_id, 'all', 'allow'
  from role r
  join permission p on p.resource = 'payment' and p.action = 'request_refund'
 where r.code = 'admin';


-- 감사자. 읽기가 아니면 막는다.
--
-- V5 의 deny 도 그 시점의 permission 만 훑었다. V12 의 주석이 「새 권한을 넣는 마이그레이션이
-- 감사자 deny 도 같이 넣어야 한다」고 남긴 자리다.
insert into role_permission (role_id, permission_id, scope, effect)
select r.role_id, p.permission_id, 'all', 'deny'
  from role r
  join permission p on p.resource = 'payment' and p.action = 'request_refund'
 where r.code = 'auditor';


-- 감사자 거부가 실제로 걸렸는지 확인한다.
--
-- 빠뜨렸을 때 아무 일도 안 일어나기 때문에 둔다 —
-- 감사자가 환불을 요청할 수 있게 되는데 오류도 로그도 안 남는다.
do $$
declare missing int;
begin
    select count(*) into missing
      from permission p
     where p.resource = 'payment'
       and p.action in ('refund', 'request_refund')
       and not exists (
           select 1
             from role_permission rp
             join role r on r.role_id = rp.role_id
            where rp.permission_id = p.permission_id
              and r.code = 'auditor' and rp.effect = 'deny');

    if missing > 0 then
        raise exception '감사자 거부가 안 걸린 결제 동작이 % 개 있다', missing;
    end if;
end $$;


-- 승인이 관리자 하나뿐인지 확인한다.
--
-- 이것도 빠뜨리면 조용하다. 나중에 누가 seller_owner 에게 payment:refund 를 주면
-- D2 R5 가 적어 둔 법 근거가 코드에서 사라지는데, 그 사실이 어디에도 안 드러난다.
-- 일부러 여는 청크는 이 검사를 같이 고치면서 왜 여는지를 적게 된다.
do $$
declare granted text;
begin
    select string_agg(r.code, ', ' order by r.code) into granted
      from role_permission rp
      join role r on r.role_id = rp.role_id
      join permission p on p.permission_id = rp.permission_id
     where p.resource = 'payment' and p.action = 'refund'
       and rp.effect = 'allow'
       and r.code <> 'admin';

    if granted is not null then
        raise exception '환급 의무자가 우리라서 승인은 관리자만이다(D2 R5). 열린 역할: %', granted;
    end if;
end $$;


-- 환불 필드 그룹.
--
-- V6 가 order 자원에 basic·shipping·payment 를 두면서 「새 자원에 그룹을 정의할 때
-- 그 자원의 기존 규칙에 연결을 다는 것이 같이 와야 한다」고 적었다. 여기가 그 자리다.
--
-- payment 그룹에 안 얹고 새로 만든 이유는 보는 사람이 다르기 때문이다(D23 「어느 쪽을 언제 쓰나」).
-- 환불은 셀러 정산에서 차감되는 돈이라(D3) 셀러가 못 보면 명세가 왜 그 금액인지 확인할 자리가 없다.
-- 반대로 결제 수단·승인번호는 셀러가 볼 이유가 없어서 payment 가 닫혀 있다.
--
-- 요청 사유는 이 그룹에 안 들어간다. 소비자가 쓴 자유 텍스트라 무엇이 들어올지 모르고,
-- 셀러에게 나가는 것이 제3자 제공이다(D2 R8). 그룹이 여는 것은 금액·상태·기한뿐이다.
insert into permission_field_group (resource, code, description) values
    ('order', 'refund', '환불 금액, 상태, 환급 기한');


-- 이미 필드 그룹 연결이 있는 규칙에만 붙인다.
--
-- V6 가 「연결이 하나도 없는 규칙은 제한이 없는 것으로 본다」고 정했다. 관리자가 그 경우다 —
-- 연결이 없어서 전부 본다. 여기서 조건 없이 붙이면 관리자 규칙에 연결이 하나 생기고,
-- 그 순간 관리자는 refund 하나만 보게 된다. 배송지도 결제도 사라진다.
--
-- 넓히려고 붙인 것이 좁히는 결과가 되는 자리라, 이 where 절이 이 마이그레이션의 핵심이다.
--
-- V6 는 g.resource = 'order' 전체를 붙였는데 그건 그 시점의 그룹만 훑었다.
-- V20 이 겪은 것과 같은 모양이라 여기서 새 그룹만 따로 붙인다.
--
-- 감사자에게도 붙는 이유는 V6 의 근거를 읽으면 나온다. 거기서 닫은 것은 payment 그룹이고
-- 근거가 「결제 수단까지 볼 이유는 없다」였다 — 금액이 아니라 결제 수단이다.
-- refund 그룹에는 결제 수단이 없고 금액·상태·기한뿐이라 그 근거가 안 걸린다.
-- 오히려 자금이 나가는 자리라 감사의 대상이다.
insert into role_permission_field (role_id, permission_id, effect, permission_field_group_id)
select rp.role_id, rp.permission_id, rp.effect, g.permission_field_group_id
  from role_permission rp
  join permission p on p.permission_id = rp.permission_id
                   and p.resource = 'order' and p.action = 'read'
  join permission_field_group g on g.resource = 'order' and g.code = 'refund'
 where rp.effect = 'allow'
   and exists (select 1 from role_permission_field f
                where f.role_id = rp.role_id and f.permission_id = rp.permission_id
                  and f.effect = rp.effect);


-- 붙인 뒤에 확인한다. order:read 를 허용받은 규칙 중 refund 를 못 보는 것이 있으면
-- 그 역할의 화면에서 환불이 조용히 사라진다 — 오류도 로그도 안 남는 종류다.
do $$
declare missing int;
begin
    select count(*) into missing
      from role_permission rp
      join permission p on p.permission_id = rp.permission_id
                       and p.resource = 'order' and p.action = 'read'
     where rp.effect = 'allow'
       and exists (select 1 from role_permission_field f
                    where f.role_id = rp.role_id and f.permission_id = rp.permission_id
                      and f.effect = rp.effect)
       and not exists (
           select 1
             from role_permission_field f
             join permission_field_group g
                  on g.permission_field_group_id = f.permission_field_group_id
            where f.role_id = rp.role_id and f.permission_id = rp.permission_id
              and f.effect = rp.effect and g.code = 'refund');

    if missing > 0 then
        raise exception 'refund 그룹이 안 붙은 order:read 규칙이 % 개 있다', missing;
    end if;
end $$;
