-- 접속기록을 고치지 못하게 하고, 파기 배치만 지우게 한다(`Q280a`).
--
-- **왜**: 안전성 확보조치 기준(고시 제2025-9호) 제8조③ 이 접속기록을 위·변조·도난·분실되지 않게 안전하게
-- 보관하라 한다(`D2` R44). `audit_log`(`V10`)에는 변경을 막는 것이 없었다 — 앱 코드에 `update` 가 없다는 것은
-- 약속일 뿐이고, 새 입구가 생기면 아무것도 안 막는다.
--
-- **왜 표시인가**: 보존 3년(`D13`)이 지나면 파기 배치가 지워야 한다. 지우기를 통째로 막으면 파기가 성립 안 하고,
-- 역할(DB 계정)로 가르려면 앱이 계정 둘을 들어야 한다. 그래서 파기 배치가 같은 트랜잭션 안에서
-- `set_config('shop.audit_purge', 'on', true)` 로 켠 표시가 있을 때만 `delete` 를 통과시킨다 —
-- 트랜잭션 로컬이라 커밋·롤백과 같이 사라진다(`V126` 과 같은 관용구).
--
-- **안 한 것 — 해시 사슬**: 행마다 앞 행의 해시를 물리면 DB 관리자의 변조까지 드러난다.
-- 지금은 인스턴스 하나·DB 역할 하나라 관리자 변조는 위협 모델 밖이다. 운영자가 여럿이 되는 날 다시 본다
-- (`security-baseline.md` 「접속기록 — 고치지 않고, 파기 배치만 지운다」).
create or replace function audit_log_guard()
    returns trigger language plpgsql as $$
begin
    if tg_op = 'TRUNCATE' then
        raise exception '접속기록은 통째로 비우지 않는다';
    end if;

    if tg_op = 'UPDATE' then
        raise exception '접속기록은 고치지 않는다 (audit_log_id=%)', old.audit_log_id;
    end if;

    if coalesce(current_setting('shop.audit_purge', true), '') <> 'on' then
        raise exception '접속기록은 파기 배치만 지운다 (audit_log_id=%)', old.audit_log_id;
    end if;

    return old;
end;
$$;

create trigger audit_log_guard
    before update or delete on audit_log
    for each row execute function audit_log_guard();

-- 파기 배치는 `delete` 만 쓴다. 통째로 비우는 길은 표시와 상관없이 닫는다.
create trigger audit_log_no_truncate
    before truncate on audit_log
    for each statement execute function audit_log_guard();

comment on trigger audit_log_guard on audit_log is
    '고치기를 막고, 지우기는 트랜잭션 로컬 shop.audit_purge = on 일 때만 통과시킨다. 안전성 확보조치 기준 제8조③';

comment on trigger audit_log_no_truncate on audit_log is
    '통째로 비우는 것을 막는다. 안전성 확보조치 기준 제8조③';
