-- 감사자 거부를 사람이 아니라 트리거가 만든다(`Q59`, `D6` 「알려진 구멍 1·2」).
--
-- **구멍이 이랬다.** 새 권한을 넣는 마이그레이션마다 「감사자 deny 도 같이 넣어라」가
-- `V12` 의 주석으로만 걸려 있었다 — 강제 지점 5위다. 아홉 청크가 그것을 지켰지만
-- **지킨 것과 막힌 것은 다르다**: 열째가 빠뜨리면 감사자가 그 권한으로 쓰기를 한다.
--
-- **읽기와 쓰기를 데이터로 가른다.** 이름으로 가르면(`action = 'read'`) 조회에 다른 이름을 붙이는
-- 날 조용히 틀린다 — 그래서 컬럼을 두고 **넣는 사람이 매번 정하게** 한다(기본값 없음).

-- ---------------------------------------------------------------------------
-- 1. 권한의 종류
-- ---------------------------------------------------------------------------

alter table permission add column kind text;

-- 백필. **넣기 전에 실물을 떠서 눈으로 대조했다**(2026-09-17):
--
--   audit:read       inquiry:answer   inquiry:block    inquiry:create   inquiry:read
--   order:approve_return  order:cancel  order:confirm  order:create     order:read
--   order:receive_return  order:reject_return  order:request_return  order:update_status
--   payment:read     payment:refund   payment:request_refund
--   product:create   product:delete   product:read     product:review   product:update
--   role:assign      role:manage      role:read
--   settlement:payout  settlement:read  settlement:request_payout
--   user:read        user:update
--
-- **서른 중 조회가 여덟이고 전부 이름이 정확히 `read` 다.** 손으로 고칠 행이 0이었다 —
-- 이 규칙이 지금은 맞다는 뜻이지, 앞으로도 맞다는 뜻이 아니라서 아래 `not null` 을 건다.
update permission set kind = case when action = 'read' then 'read' else 'write' end;

alter table permission alter column kind set not null;

alter table permission add constraint permission_kind_check
    check (kind in ('read', 'write'));

-- ---------------------------------------------------------------------------
-- 2. 읽기만 하는 역할
-- ---------------------------------------------------------------------------

-- 이름이 `is_` 로 시작한다(`D22` 「불리언은 `is_` 로 시작한다」) — `SchemaNamingTest` 가 잰다.
-- 역할 쪽에도 데이터로 둔다. `code = 'auditor'` 를 트리거가 글자로 비교하게 하면
-- **읽기 전용 역할이 둘째로 생기는 날** 그 트리거를 고쳐야 한다.
alter table role add column is_read_only boolean not null default false;

update role set is_read_only = true where code = 'auditor';

-- ---------------------------------------------------------------------------
-- 3. 거부를 만드는 트리거 둘
-- ---------------------------------------------------------------------------

-- 새 쓰기 권한이 생기면 읽기 전용 역할 전부에 거부를 단다.
--
-- **`on conflict do nothing` 이 손으로 넣은 것과 부딪치지 않게 한다**(`V5`·`V12`~`V64`).
-- 기본키가 `(role_id, permission_id, effect)` 라 허용과 거부가 같이 살고, 같은 거부는 한 번만 남는다.
create or replace function deny_write_to_read_only_roles() returns trigger as $$
begin
    if new.kind = 'write' then
        insert into role_permission (role_id, permission_id, scope, effect)
        select r.role_id, new.permission_id, 'all', 'deny'
          from role r
         where r.is_read_only
        on conflict do nothing;
    end if;
    return null;
end;
$$ language plpgsql;

create trigger permission_denies_read_only_roles
    after insert on permission
    for each row execute function deny_write_to_read_only_roles();

-- 역할이 읽기 전용이 되면 그 시점의 쓰기 권한 전부에 거부를 단다.
-- `after insert or update of read_only` 라 새로 만든 읽기 전용 역할도 같은 길로 채워진다.
create or replace function deny_writes_for_read_only_role() returns trigger as $$
begin
    if new.is_read_only then
        insert into role_permission (role_id, permission_id, scope, effect)
        select new.role_id, p.permission_id, 'all', 'deny'
          from permission p
         where p.kind = 'write'
        on conflict do nothing;
    end if;
    return null;
end;
$$ language plpgsql;

create trigger role_denies_writes_when_read_only
    after insert or update of is_read_only on role
    for each row execute function deny_writes_for_read_only_role();

-- ---------------------------------------------------------------------------
-- 4. 종류는 못 고친다
-- ---------------------------------------------------------------------------

-- 고치면 **이미 만든 거부가 남거나 빠진다.** `write` 를 `read` 로 바꿔도 거부는 그대로 남고,
-- `read` 를 `write` 로 바꿔도 위 트리거는 `insert` 에만 걸려 있어 안 돈다.
-- 종류를 바꿔야 할 일이 생기면 **권한을 새로 만든다** — 그것이 이름이 바뀌는 일이기도 하다.
create or replace function reject_permission_kind_change() returns trigger as $$
begin
    raise exception '권한의 종류는 못 고친다. 새 권한을 만든다(Q59)';
end;
$$ language plpgsql;

create trigger permission_kind_is_immutable
    before update of kind on permission
    for each row
    when (old.kind is distinct from new.kind)
    execute function reject_permission_kind_change();
