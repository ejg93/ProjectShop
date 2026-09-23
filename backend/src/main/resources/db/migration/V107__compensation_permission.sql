-- 손해배상 판정 권한(`43a-4c`, `D2` R38·R39).
--
-- **판정 표는 `V69` 가 세웠고 넣는 길이 `psql` 뿐이었다.** 그 상태면 배상이 데이터로 안 남는다 —
-- 게시 중단(`59-2`)이 지난 자리와 같다. 입구를 열면서 누가 여는지를 여기서 정한다.
--
-- **판정은 관리자만이다.** 액수를 법이 안 정해서(`V69` 가 원문을 적었다) 사람이 정한 값이 곧 정산의 줄이 된다 —
-- 셀러에게 열면 자기 몫에서 빠질 금액을 스스로 정하고, 직원·고객에게 열 이유가 없다.
-- **조회는 감사자까지다.** 판정은 분쟁 처리의 기록이라 감사 대상이고, 쓰기의 거부는 `V75` 트리거가 단다.
insert into permission (resource, action, kind, description) values
    ('compensation', 'read', 'read', '손해배상 판정을 조회한다'),
    ('compensation', 'decide', 'write', '미인도·지연인도 손해배상을 판정한다(금액·부담 주체·사유)');

insert into role_permission (role_id, permission_id, scope, effect)
select r.role_id, p.permission_id, 'all', 'allow'
  from role r
  join permission p on p.resource = 'compensation'
 where r.code = 'admin';

insert into role_permission (role_id, permission_id, scope, effect)
select r.role_id, p.permission_id, 'all', 'allow'
  from role r
  join permission p on p.resource = 'compensation' and p.action = 'read'
 where r.code = 'auditor';

-- 판정이 관리자 밖으로 나갔는지 본다(`V57`·`V58` 과 같은 모양). 빠뜨려도 아무 오류가 안 난다 —
-- 셀러가 자기 배상액을 정하는 길이 조용히 열릴 뿐이다.
do $$
declare opened text;
begin
    select string_agg(r.code || ':' || p.action || ':' || rp.scope, ', ' order by r.code, p.action) into opened
      from role_permission rp
      join role r on r.role_id = rp.role_id
      join permission p on p.permission_id = rp.permission_id
     where p.resource = 'compensation' and rp.effect = 'allow'
       and not ((r.code = 'admin' and rp.scope = 'all')
                or (r.code = 'auditor' and p.action = 'read' and rp.scope = 'all'));

    if opened is not null then
        raise exception '손해배상 권한이 정한 범위 밖으로 열렸다: %', opened;
    end if;
end $$;
