-- 비밀번호 재설정 토큰(`5c-1`).
--
-- **토큰 원문을 안 담는다.** 표가 새면 그 값으로 남의 비밀번호를 바꿀 수 있어서,
-- 비밀번호와 같은 취급을 한다 — 해시만 저장하고 대조도 해시로 한다(`D14`).
--
-- **재사용을 앱이 아니라 여기서 막는다.** `used_at` 이 차면 다시 못 쓴다는 규칙을
-- 서비스 코드에만 두면 새 입구가 생길 때 빠뜨린다(`D23` 축 2 — 제약이 2위, 앱 검증이 3위).
create table password_reset_token (
    password_reset_token_id bigint generated always as identity primary key,

    user_id    bigint      not null
        references app_user (user_id) on delete cascade,

    -- 토큰 원문의 해시. 대조는 이 값으로만 한다.
    token_hash text        not null,

    issued_at  timestamptz not null default now(),

    -- 이 시각을 지나면 못 쓴다. 발급할 때 박제한다 — 수명 규칙이 바뀌어도 이미 나간 것은 안 바뀐다.
    expires_at timestamptz not null,

    -- 쓴 시각. 차 있으면 끝난 토큰이다.
    used_at    timestamptz,

    constraint password_reset_token_hash_length_check
        check (length(token_hash) between 1 and 200),

    -- 쓴 시각이 발급보다 앞설 수 없다.
    constraint password_reset_token_used_after_issued_check
        check (used_at is null or used_at >= issued_at),

    -- 수명이 0 이하일 수 없다.
    constraint password_reset_token_expiry_after_issued_check
        check (expires_at > issued_at)
);

-- 같은 해시가 둘일 수 없다. 대조가 한 행으로 떨어진다.
create unique index password_reset_token_hash_idx
    on password_reset_token (token_hash);

-- **사람마다 살아 있는 토큰은 하나다.** 새로 발급하면 앞의 것을 써 버린 것으로 닫는다 —
-- 여러 개가 살아 있으면 메일을 여러 번 받은 사람의 옛 링크가 계속 통한다.
create unique index password_reset_token_live_idx
    on password_reset_token (user_id)
    where used_at is null;

comment on table password_reset_token is
    '비밀번호 재설정 1회용 토큰. 원문이 아니라 해시를 담는다(D14, 5c-1)';
comment on column password_reset_token.token_hash is
    '토큰 원문의 해시. 원문은 메일로만 나가고 어디에도 안 남는다(D16 A09)';
comment on column password_reset_token.used_at is
    '쓴 시각. 차면 재사용을 부분 유니크 인덱스가 막는다';

-- 재설정 안내 판(`5c-1`). 사건 이름과 코드가 같다(`NotificationEventType.PASSWORD_RESET`).
--
-- **링크에 토큰 원문이 실린다.** 그것이 이 메일이 유일하게 원문을 아는 자리고,
-- 우리 쪽에는 해시만 남는다.
insert into notification_template (code, version, subject, body, kind) values
    ('password_reset', 1,
     '[프로젝트샵] 비밀번호 재설정 안내',
     '프로젝트샵입니다.' || chr(10) ||
     '아래 주소에서 비밀번호를 다시 정해 주세요.' || chr(10) ||
     '{{reset_url}}' || chr(10) ||
     '이 링크는 {{expires_at}}까지만 쓸 수 있고 한 번 쓰면 사라집니다.' || chr(10) ||
     '요청하신 적이 없다면 이 메일을 무시하셔도 됩니다.',
     'transactional');
