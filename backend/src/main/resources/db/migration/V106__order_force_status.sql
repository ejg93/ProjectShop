-- 관리자 강제 전이 권한(`16c`).
--
-- **상태 축을 비켜 가는 유일한 동작이다.** `OrderStatusPolicy` 가 동작마다 열린 상태를 들고 있고 관리자도
-- 거기 걸린다 — 끝난 묶음·배송 중인 묶음에는 취소가 안 열린다. 그 표에 예외를 파지 않고 동작을 새로 세운다:
-- 그래야 **이 권한을 누구에게 줬는지가 데이터로 남는다**(`OrderStatusPolicy` 의 주석, `D7` 「관리자 강제 전이」).
--
-- **갈 수 있는 곳은 코드가 닫는다** — 취소·배송중·배송완료 셋(2026-09-23 사용자 선택). 끝난 상태에서 나가는 것은
-- 안 연다: 재고를 다시 빼고 거래 종료 시각을 비우는 코드가 없어서다. 사유는 `order_status_history_admin_reason_check`
-- 가 이미 요구한다(`V18`).
insert into permission (resource, action, kind, description) values
    ('order', 'force_status', 'write', '전이표와 상태 축 밖으로 배송 묶음을 옮긴다(관리자 CS)');

insert into role_permission (role_id, permission_id, scope, effect)
select r.role_id, p.permission_id, 'all', 'allow'
  from role r
  join permission p on p.resource = 'order' and p.action = 'force_status'
 where r.code = 'admin';

-- 관리자에게만 열렸는지 확인한다. 셀러에게 열리면 자기 묶음의 청약철회 기산점을 밀 수 있고(`D7`),
-- 감사자에게 열리면 읽기 역할이 쓰기를 갖는다. 빠뜨려도 아무 오류가 안 난다.
do $$
declare opened text;
begin
    select string_agg(r.code || ':' || rp.scope, ', ' order by r.code) into opened
      from role_permission rp
      join role r on r.role_id = rp.role_id
      join permission p on p.permission_id = rp.permission_id
     where p.resource = 'order' and p.action = 'force_status' and rp.effect = 'allow'
       and not (r.code = 'admin' and rp.scope = 'all');

    if opened is not null then
        raise exception '강제 전이가 관리자 밖으로 열렸다: %', opened;
    end if;
end $$;
