-- 감사 로그를 볼 권한.
--
-- V10 이 테이블을 만들 때 이 권한을 같이 안 만들었다. 쌓기만 하고 꺼내는 경로가 없었으므로
-- 판정할 것도 없었다. 조회 API 가 생기는 지금 만든다.

insert into permission (resource, action, description) values
    ('audit', 'read', '감사 로그 조회');

-- 관리자와 감사자만 준다. 범위는 전체다.
--
-- 고객에게 "자기 감사 기록 보기"(own)를 주지 않는다. 요건에 없고,
-- 주면 audit_log 가 들고 있는 개인정보(actor_user_id, detail)의 노출면이 넓어진다.
-- 이 테이블은 보존 3년이라 그 면적이 오래 남는다(V10).
insert into role_permission (role_id, permission_id, scope, effect)
select r.role_id, p.permission_id, 'all', 'allow'
  from role r
  cross join permission p
 where r.code in ('admin', 'auditor')
   and p.resource = 'audit' and p.action = 'read';

-- 감사자는 읽기만 한다는 V5 의 거부 규칙이 이 권한에도 걸리는지 확인해 둔다.
-- action 이 'read' 라 걸리지 않는 것이 맞다. 걸리면 감사자가 감사 로그를 못 본다.
--
-- V5 의 조건이 action 이름에 기대고 있다는 것은 permission-rules.md 의 「알려진 구멍 2」다.
-- 여기서 고치지 않는다 — 고치려면 permission 에 읽기/쓰기 분류 컬럼이 붙어야 하고,
-- 그건 판정 엔진까지 건드리는 별도 청크다.
do $$
declare denied int;
begin
    select count(*) into denied
      from role_permission rp
      join role r on r.role_id = rp.role_id
      join permission p on p.permission_id = rp.permission_id
     where r.code = 'auditor' and p.resource = 'audit' and rp.effect = 'deny';

    if denied > 0 then
        raise exception '감사자에게 audit 거부 규칙이 걸렸다. V5 의 조건을 다시 본다';
    end if;
end $$;
