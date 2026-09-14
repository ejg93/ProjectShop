-- 이메일 변경 대기(`5e-1`).
--
-- **확인 전에는 `app_user.email` 을 안 바꾼다.** 바로 덮어쓰면 오타를 친 순간 그 계정으로는
-- 아무것도 못 받는다 — 바뀌었다는 알림조차 없는 주소로 간다. 대기 주소를 여기 두면
-- **쓰던 주소가 살아 있어서** 링크가 안 와도 잃는 것이 없다(`D2` R28 정정 요구).
--
-- 토큰을 다루는 방식은 `password_reset_token`(`V67`)과 같다 — 해시만 담고,
-- 살아 있는 것은 사람마다 하나고, 재사용은 부분 유니크 인덱스가 막는다.
create table email_change_request (
    email_change_request_id bigint generated always as identity primary key,

    user_id    bigint      not null
        references app_user (user_id) on delete cascade,

    -- 바꾸려는 주소. 확인될 때 `app_user.email` 로 옮긴다.
    new_email  text        not null,

    token_hash text        not null,

    issued_at  timestamptz not null default now(),
    expires_at timestamptz not null,

    -- 쓴 시각. 차 있으면 끝난 요청이다.
    used_at    timestamptz,

    constraint email_change_request_new_email_length_check
        check (length(new_email) between 3 and 254),

    constraint email_change_request_token_hash_length_check
        check (length(token_hash) between 1 and 200),

    constraint email_change_request_used_after_issued_check
        check (used_at is null or used_at >= issued_at),

    constraint email_change_request_expiry_after_issued_check
        check (expires_at > issued_at)
);

create unique index email_change_request_hash_idx
    on email_change_request (token_hash);

-- **사람마다 살아 있는 요청은 하나다.** 여러 개가 살아 있으면 마지막에 누른 링크가 이기는데,
-- 그 순서를 사용자가 모른다 — 어느 주소로 바뀌었는지도 모르게 된다.
create unique index email_change_request_live_idx
    on email_change_request (user_id)
    where used_at is null;

comment on table email_change_request is
    '이메일 변경 대기. 확인될 때까지 app_user.email 은 안 바뀐다(D2 R28, 5e-1)';
comment on column email_change_request.new_email is
    '바꾸려는 주소. 확인 링크가 이 주소로 간다 — 받을 수 있다는 것이 곧 확인이다';

-- 확인 안내 판(`5e-1`). 사건 이름과 코드가 같다(`NotificationEventType.EMAIL_CHANGE`).
--
-- **이 메일만 새 주소로 간다.** 나머지 통지는 계정에 적힌 주소로 가므로, 이 판이
-- 「그 주소를 받을 수 있나」를 묻는 유일한 자리다.
insert into notification_template (code, version, subject, body, kind) values
    ('email_change', 1,
     '[프로젝트샵] 이메일 주소 확인',
     '프로젝트샵입니다.' || chr(10) ||
     '이 주소로 계정 이메일을 바꾸려면 아래 주소를 눌러 주세요.' || chr(10) ||
     '{{confirm_url}}' || chr(10) ||
     '이 링크는 {{expires_at}}까지만 쓸 수 있고 한 번 쓰면 사라집니다.' || chr(10) ||
     '요청하신 적이 없다면 이 메일을 무시하셔도 됩니다. 계정은 바뀌지 않습니다.',
     'transactional');
