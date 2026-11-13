-- 2차 인증 등록 표(`Q451`). 셀러·관리자·감사자 외부 접속에 안전한 인증수단을 건다 — 안전성 확보조치 기준 제6조제2항(`D2` R45).
--
-- **계정당 하나다** — 기본키가 `user_id` 다(`seller_daily_sales` 와 같은 꼴, `naming-rules.md` 「기본키」).
-- **확인된 등록만 행이 선다.** 등록 중인 비밀은 세션에 싸서 두고(`Q452`) 첫 코드가 맞을 때 여기 넣는다 —
-- 「등록 중」 상태 칸을 두지 않아 미확정 비밀로 로그인하는 길이 구조로 없다.
--
-- **비밀은 암호문만 둔다**(고시 제7조① — 인증정보는 안전한 암호 알고리즘으로 암호화해 저장). 원문이 있어야 코드를
-- 계산하므로 해시가 아니라 AES-GCM 이고 키는 `SECOND_FACTOR_SECRET_KEY` 다(`webhook_endpoint.secret_ciphertext` 와 같은 꼴, `V110`).
--
-- **재사용 금지는 `last_used_step` 이 쥔다**(RFC 6238 §5.2). 검증이 `last_used_step < :step` 조건부 UPDATE 라
-- 같은 스텝이나 앞 스텝의 코드를 두 번째로 내면 0행이다.
--
-- **탈퇴가 지운다**(`WithdrawalService`). 탈퇴는 `app_user` 를 `update` 해서 `cascade` 가 안 걸린다 —
-- `on delete cascade` 는 파기 배치가 계정 행을 지울 때 몫이다(`coding-rules.md` 「cascade 를 파기 수단으로 쓰지 않는다」).
create table user_second_factor (
    user_id bigint not null primary key references app_user (user_id) on delete cascade,
    secret_ciphertext bytea not null,
    secret_key_version int not null,
    last_used_step bigint not null,
    enrolled_at timestamptz not null default now(),

    constraint user_second_factor_secret_key_version_check check (secret_key_version > 0),
    constraint user_second_factor_last_used_step_check check (last_used_step >= 0)
);

comment on table user_second_factor is
    '2차 인증(TOTP) 등록(Q451, R45). 확인된 등록만 행이 선다. 비밀은 AES-GCM 암호문만 둔다(고시 제7조①)';
comment on column user_second_factor.secret_ciphertext is
    'IV(12바이트) 뒤에 암호문과 태그. 키는 SECOND_FACTOR_SECRET_KEY 이고 secret_key_version 판이다. 묶음 값은 user_id';
comment on column user_second_factor.last_used_step is
    '마지막으로 통과한 TOTP 스텝(30초). 이 값 이하 스텝의 코드는 다시 안 받는다(RFC 6238 §5.2)';
