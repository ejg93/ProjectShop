-- 문의 거두기를 제 권한으로 가른다(`Q239`, `Q234` 에서 드러났다). 거두기가 `inquiry:read` 판정을 빌려 써서
-- **읽기 전용인 감사자가 `read all` 로 남의 문의를 거둘 수 있었다.** 읽기 권한에는 `V75` 트리거가 거부를 안 단다.
--
-- **쓰기 권한을 새로 판다** — `kind = 'write'` 라 `V75` 트리거가 감사자 거부를 스스로 단다. 서비스에서 역할을
-- 따로 거르는 길은 부여표 밖의 둘째 축이라 접었다(`permission-rules.md` 「is_read_only」 절이 이미 버린 길).
--
-- 받는 것: 고객은 자기 문의(`own`), 관리자는 대신 거둔다(`all`). 셀러 대표는 안 받는다 — 자기 상품에 달린
-- 불리한 질문을 지우는 자리가 된다(`InquiryService.withdraw` 주석).

insert into permission (resource, action, kind, description) values
    ('inquiry', 'withdraw', 'write', '자기 문의를 거둔다');

insert into role_permission (role_id, permission_id, scope, effect)
select r.role_id, p.permission_id, 'own', 'allow'
  from role r
  join permission p on p.resource = 'inquiry' and p.action = 'withdraw'
 where r.code = 'customer';

insert into role_permission (role_id, permission_id, scope, effect)
select r.role_id, p.permission_id, 'all', 'allow'
  from role r
  join permission p on p.resource = 'inquiry' and p.action = 'withdraw'
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
     where p.resource = 'inquiry' and p.action = 'withdraw' and rp.effect = 'allow'
       and not ((r.code = 'customer' and rp.scope = 'own') or (r.code = 'admin' and rp.scope = 'all'));
    if opened is not null then
        raise exception '문의 거두기가 정한 범위 밖으로 열렸다: %', opened;
    end if;

    -- 트리거가 감사자 거부를 달았나. 안 달렸으면 `V75` 가 이 권한을 못 본 것이다
    select count(*) into denied
      from role_permission rp
      join role r on r.role_id = rp.role_id
      join permission p on p.permission_id = rp.permission_id
     where p.resource = 'inquiry' and p.action = 'withdraw' and rp.effect = 'deny' and r.code = 'auditor';
    if denied <> 1 then
        raise exception '감사자의 문의 거두기 거부가 하나여야 한다: %', denied;
    end if;
end $$;
