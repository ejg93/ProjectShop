-- 매출 통계를 보는 권한(`41`).
--
-- **정산과 다른 자원이다.** 정산(`settlement:read`)은 우리가 셀러에게 줄 돈의 계산이고 이것은 그 셀러가 판 것의
-- 날짜별 합이다. 하나로 두면 「매출은 보되 정산은 못 보는 역할」(마케팅 담당 같은)을 나중에 못 가른다(`D6`).
--
-- **여는 범위는 정산과 같다** — 대표는 자기 셀러(`seller`), 관리자와 감사자는 전부(`all`). 직원(`seller_staff`)
-- 에게는 안 준다: 일별 매출은 그 셀러의 거래 규모라 정산액과 같은 급이다. 대표가 필요하면 역할 편집으로 연다.
insert into permission (resource, action, kind, description) values
    ('sales_stats', 'read', 'read', '셀러의 날짜별 매출 합계를 조회한다');

insert into role_permission (role_id, permission_id, scope, effect)
select r.role_id, p.permission_id, 'seller', 'allow'
  from role r
  join permission p on p.resource = 'sales_stats' and p.action = 'read'
 where r.code = 'seller_owner';

insert into role_permission (role_id, permission_id, scope, effect)
select r.role_id, p.permission_id, 'all', 'allow'
  from role r
  join permission p on p.resource = 'sales_stats' and p.action = 'read'
 where r.code in ('admin', 'auditor');

-- 대표가 seller 범위로만 열렸는지, 고객·직원에게 안 열렸는지 확인한다(`V56` 과 같은 두 겹).
--
-- all 로 들어가면 셀러가 남의 매출을 본다. 빠뜨려도 아무 오류가 안 난다 — 조회가 조용히 넓어질 뿐이다.
do $$
declare opened text;
begin
    select string_agg(r.code || ':' || rp.scope, ', ' order by r.code) into opened
      from role_permission rp
      join role r on r.role_id = rp.role_id
      join permission p on p.permission_id = rp.permission_id
     where p.resource = 'sales_stats' and rp.effect = 'allow'
       and not ((r.code = 'seller_owner' and rp.scope = 'seller')
                or (r.code in ('admin', 'auditor') and rp.scope = 'all'));

    if opened is not null then
        raise exception '매출 통계가 정한 범위 밖으로 열렸다: %', opened;
    end if;
end $$;
