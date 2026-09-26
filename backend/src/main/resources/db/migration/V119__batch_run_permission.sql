-- 기준일 배치를 손으로 돌리는 권한(`Q241`). 지금 배치를 돌리는 길이 cron 뿐이라 측정(`42`)·부하(`70`)·장애 실험(`68`)이
-- 04:00~05:00 을 기다려야 했다. 입구는 `POST /api/admin/batches/{name}/runs` 다.
--
-- **쓰기 권한이다** — 회차가 정산·파기·자동 확정을 실제로 돈다. `kind = 'write'` 라 `V75` 트리거가 감사자 거부를 단다.
-- 관리자만 `all` 로 받는다. 대상 자원이 없는 동작이라 범위는 `all` 하나뿐이다.

insert into permission (resource, action, kind, description) values
    ('batch', 'run', 'write', '기준일 배치를 지금 돌린다');

insert into role_permission (role_id, permission_id, scope, effect)
select r.role_id, p.permission_id, 'all', 'allow'
  from role r
  join permission p on p.resource = 'batch' and p.action = 'run'
 where r.code = 'admin';

do $$
declare
    opened text;
    denied integer;
begin
    select string_agg(r.code || ':' || rp.scope, ', ' order by r.code) into opened
      from role_permission rp
      join role r on r.role_id = rp.role_id
      join permission p on p.permission_id = rp.permission_id
     where p.resource = 'batch' and rp.effect = 'allow'
       and not (r.code = 'admin' and rp.scope = 'all');
    if opened is not null then
        raise exception '배치 실행이 관리자 밖으로 열렸다: %', opened;
    end if;

    select count(*) into denied
      from role_permission rp
      join role r on r.role_id = rp.role_id
      join permission p on p.permission_id = rp.permission_id
     where p.resource = 'batch' and p.action = 'run' and rp.effect = 'deny' and r.code = 'auditor';
    if denied <> 1 then
        raise exception '감사자의 배치 실행 거부가 하나여야 한다: %', denied;
    end if;
end $$;
