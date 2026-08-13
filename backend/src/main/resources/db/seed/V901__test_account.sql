-- 손으로 화면을 두드릴 때 쓰는 계정 하나.
--
-- V900 의 데모 계정과 목적이 다르다. 그쪽은 권한 축을 데이터로 밟으려고 여섯을 세워 둔 것이고,
-- 이쪽은 그냥 로그인해서 화면을 보려는 것이라 비밀번호가 짧다.
--
-- 비밀번호가 '1' 이다. 앱 규칙(`D14` — 8자 이상)에 걸리므로 가입 API 로는 못 만든다.
-- 여기서 넣을 수 있는 것은 규칙이 앱 검증(강제 지점 3위)에만 있고 DB 제약이 아니어서다.
-- 규칙을 DB 로 내리지 않은 이유는 해시를 저장하기 때문이다 - 저장된 값만 봐서는 길이를 모른다.
--
-- 로컬 전용 프로필에서만 적용된다(`application-local.yml`). 배포가 생기면 이 파일을 지운다.
--
-- 이메일 형식이 필요하다. 로그인 화면의 입력칸이 type=email 이라 'test' 만으로는 브라우저가 막는다.

-- 이미 있으면 아무것도 안 한다. 손으로 먼저 넣어 둔 DB 에서도 그대로 돌아야 한다.
insert into app_user (email, password_hash, display_name)
select 'test@test.local', '{bcrypt}' || crypt('1', gen_salt('bf', 10)), '테스트'
 where not exists (select 1 from app_user where lower(email) = 'test@test.local');

-- 고객 역할만 준다. 셀러나 관리자로 보려면 V900 의 계정을 쓴다.
insert into user_role (user_id, role_id)
select u.user_id, r.role_id
  from app_user u
  join role r on r.code = 'customer'
 where lower(u.email) = 'test@test.local'
   and not exists (select 1 from user_role ur
                    where ur.user_id = u.user_id and ur.role_id = r.role_id);

-- 필수 동의. 가입 흐름을 안 타고 넣는 것이라 사건을 직접 적는다(`5-0`).
insert into user_consent (user_id, consent_item_id, granted, source)
select u.user_id, ci.consent_item_id, true, 'signup'
  from app_user u
  join consent_item ci on ci.is_required
 where lower(u.email) = 'test@test.local'
   and not exists (select 1 from user_consent uc
                    where uc.user_id = u.user_id and uc.consent_item_id = ci.consent_item_id);
