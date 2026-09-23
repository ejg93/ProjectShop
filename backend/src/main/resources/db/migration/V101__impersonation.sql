-- 관리자가 사용자 시점으로 본다(`16b`, 임퍼소네이션).
--
-- **「누가 시켰나」가 안 남으면 대행은 그냥 권한 우회다**(`D14`, `D16`). 대행 중에는 요청의 주인이 그 사용자라
-- 감사 기록의 `actor_user_id` 가 그 사람이 된다 — 시킨 관리자가 따로 적혀야 두 사람이 갈린다.

-- ---------------------------------------------------------------------------
-- 1. 감사 기록에 시킨 사람 칸
-- ---------------------------------------------------------------------------

-- `actor_user_id` 와 같은 이유로 외래키를 안 건다 — 계정이 파기돼도 기록은 남는다(`V10`).
alter table audit_log add column impersonator_user_id bigint;

comment on column audit_log.impersonator_user_id is
    '대행 중에 남은 기록이면 대행을 시킨 관리자(16b). 대행이 아니면 비어 있다';

-- ---------------------------------------------------------------------------
-- 2. 누가 대행할 수 있나 — 관리자만
-- ---------------------------------------------------------------------------

insert into permission (resource, action, kind, description) values
    ('user', 'impersonate', 'write', '다른 사용자의 시점으로 화면을 본다. 대행 중에는 쓰기가 막힌다');

insert into role_permission (role_id, permission_id, scope)
select r.role_id, p.permission_id, 'all'
  from permission p
  join role r on r.code = 'admin'
 where p.resource = 'user' and p.action = 'impersonate';

-- **관리자 밖으로 안 연다.** 남의 시점으로 보는 것은 그 사람의 주문·주소·문의를 전부 보는 것이다 —
-- 셀러나 감사자에게 열리는 순간 그 사람들이 고객 정보를 통째로 보는데 오류도 로그도 안 남는다.
-- 일부러 여는 청크는 이 검사를 같이 고치면서 왜 여는지를 적게 된다(`V58` 과 같은 자리).
do $$
declare granted text;
begin
    select string_agg(r.code, ', ' order by r.code) into granted
      from role_permission rp
      join role r on r.role_id = rp.role_id
      join permission p on p.permission_id = rp.permission_id
     where p.resource = 'user' and p.action = 'impersonate'
       and rp.effect = 'allow' and r.code <> 'admin';

    if granted is not null then
        raise exception '대행은 관리자만 한다. 열린 역할: %', granted;
    end if;
end
$$;
