-- 감사 로그. "누가 무슨 권한으로 무엇을 했나" 를 남긴다.
--
-- 관측 로그(D16)와 목적도 보관 위치도 다르다. 그쪽은 "이 요청이 어디서 느려졌나" 를
-- 파일에 남기고 지워도 되지만, 이쪽은 DB 에 남기고 함부로 못 지운다.
--
-- 보존 3년. D13 이 이 청크로 미뤄 둔 결정이다. 주문 5년보다 짧게 잡은 이유는
-- 이 테이블에 actor_user_id 가 남아서 개인정보 파기의 예외가 되기 때문이다.
-- 예외 기간이 길수록 그 자체가 비용이다.

create table audit_log (
    audit_log_id  bigint generated always as identity primary key,

    -- 무슨 일이 있었나. 'permission.denied', 'role.granted' 같은 점 표기다.
    -- 종류별로 테이블을 나누지 않은 이유는 조회가 대부분 "이 사람이 무엇을 했나" 라서다.
    -- 나누면 그 질문에 답할 때마다 union 을 해야 한다.
    event_type    text        not null,

    -- 누가 했나. 계정이 파기돼도 남는다(D13). 그래서 외래키를 걸지 않는다.
    -- 걸면 파기 배치가 계정을 지울 때 이 행까지 끌고 가거나 파기 자체가 막힌다.
    actor_user_id bigint,

    -- 무엇에 대해 했나. 'order' + 주문 id 같은 것. 자원이 없는 사건이면 비어 있다.
    target_type   text,
    target_id     bigint,

    -- 사건마다 담을 것이 달라서 열로 못 뺀다. 판정 근거, 역할 코드, 스코프 같은 것이 들어간다.
    -- 조회 조건이 되는 값은 여기 두지 말고 위의 열로 뺀다. jsonb 안을 조건으로 걸기 시작하면
    -- 무엇이 들어 있는지 아무도 모르는 채로 쿼리만 는다.
    detail        jsonb       not null default '{}'::jsonb,

    -- 파기 배치가 이 값을 본다(청크 10a). 기준은 KST 가 아니라 저장된 순간이다(D10).
    created_at    timestamptz not null default now()
);

comment on table audit_log is
    '누가 무슨 권한으로 무엇을 했나. 보존 3년. 관측 로그(파일)와 다르다';

comment on column audit_log.actor_user_id is
    '외래키를 안 건다. 계정이 파기돼도 이 기록은 남아야 감사가 성립한다';

-- "이 사람이 무엇을 했나" 가 가장 흔한 조회다. 최근 것부터 본다.
create index audit_log_actor_idx on audit_log (actor_user_id, created_at desc);

-- "이 주문에 무슨 일이 있었나". 자원이 없는 사건은 인덱스에 안 들어간다.
create index audit_log_target_idx on audit_log (target_type, target_id, created_at desc)
    where target_type is not null;

-- 파기 배치가 오래된 것부터 훑는다.
create index audit_log_created_at_idx on audit_log (created_at);
