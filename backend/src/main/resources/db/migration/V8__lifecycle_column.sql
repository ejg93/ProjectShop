-- 수명과 업무 상태를 한 컬럼에 섞어 놨다.
--
-- app_user.status 의 'suspended' 는 복구되는 업무 상태고 'withdrawn' 은 종료다.
-- seller.status 의 'closed' 도 마찬가지다. 축이 서로 직교하는데 한 컬럼에 있다.
--
-- 섞으면 두 가지가 무너진다.
--   1. 복구할 때 이전 상태를 모른다. 'withdrawn' 이 이전 값을 덮어써서 사라진다
--   2. "살아 있는 것" 을 고르는 조건이 상태가 늘 때마다 흔들린다.
--      status = 'active' 로 쓰면 정지 계정이 빠지고, status != 'withdrawn' 으로 쓰면
--      상태가 추가될 때마다 이 조건을 다시 봐야 한다
--
-- 수명을 별도 컬럼으로 뺀다. 조건이 deleted_at is null 하나로 고정된다.

alter table app_user add column deleted_at timestamptz;
alter table seller   add column deleted_at timestamptz;

comment on column app_user.deleted_at is '수명. null 이면 존재한다. 업무 상태(status)와 축이 다르다';
comment on column seller.deleted_at   is '수명. null 이면 존재한다. 업무 상태(status)와 축이 다르다';

-- 기존 종료 상태를 수명 컬럼으로 옮긴다. 지금 데이터가 없지만 규칙을 코드로 남긴다.
-- 이전 업무 상태를 알 수 없으므로 active 로 되돌린다. 이것이 섞어 둔 것의 대가다.
update app_user set deleted_at = now(), status = 'active' where status = 'withdrawn';
update seller   set deleted_at = now(), status = 'active' where status = 'closed';

alter table app_user drop constraint app_user_status_check;
alter table app_user add constraint app_user_status_check
    check (status in ('active', 'suspended'));

alter table seller drop constraint seller_status_check;
alter table seller add constraint seller_status_check
    check (status in ('active', 'suspended'));

-- 살아 있는 행만 고르는 조회가 대부분이라 부분 인덱스를 깔아 둔다.
create index app_user_alive_idx on app_user (user_id) where deleted_at is null;
create index seller_alive_idx   on seller (seller_id)   where deleted_at is null;

-- 이메일·이름·비밀번호 해시는 파기 대상이라 not null 을 못 건다(R9, 청크 5i).
-- 그렇다고 보장을 버리지 않는다. 원래 지키려던 것은 "모든 계정에 이메일이 있다" 가 아니라
-- "살아 있는 계정에 이메일이 있다" 였고, not null 은 그것의 근사였다.
--
-- 수명 컬럼이 생긴 지금은 그 조건을 그대로 적을 수 있다. 제약을 뺀 것이 아니라 맞는 제약으로 바꾼 것이다.
-- 이게 없으면 가입 코드의 버그로 이메일 없는 계정이 생겨도 DB 가 안 막는다.
alter table app_user add constraint app_user_alive_fields_check check (
    deleted_at is not null
    or (email is not null and password_hash is not null and display_name is not null)
);

-- 파기 배치(5i)가 대상을 고르는 조건이다. 아직 안 비운 탈퇴 계정만 들어간다.
-- 비우고 나면 인덱스에서 빠져서, 두 번 돌아도 훑을 것이 없다.
create index app_user_purge_idx on app_user (deleted_at)
 where deleted_at is not null and email is not null;
