-- 데모 데이터. 로컬에서 손으로 확인할 때 쓴다.
--
-- **이 파일은 db/migration 에 없다.** local 프로필에서만 Flyway 의 location 에 더해진다.
-- 참조 데이터(역할·권한·동의 항목)와 성격이 다르기 때문이다 —
-- 그쪽은 없으면 앱이 안 돌고, 이쪽은 운영에 들어가면 안 된다.
--
-- 버전을 V900 으로 띄워 둔 것은 스키마 마이그레이션과 번호가 부딪히지 않게 하려는 것이다.
-- 스키마가 V100 까지 갈 일은 없다.
--
-- 비밀번호는 전부 `demo-password-1234` 다. 로컬 전용이라 적어 둔다 —
-- 적어 두지 않으면 해시만 남아서 아무도 못 쓴다.

-- 해시를 미리 계산해 박지 않고 DB 에서 만든다. 박아 두면 그 값이 어느 알고리즘·비용으로
-- 만들어진 것인지 알 수 없고, 규칙이 바뀔 때 갱신할 방법도 없다.
create extension if not exists pgcrypto;

-- 셀러 둘. 신고번호가 있는 쪽과 면제인 쪽을 하나씩 둔다(D2 R1).
-- 둘 다 active 라 신원정보가 전부 차 있어야 한다 — seller_verified_fields_check 가 막는다.
insert into seller (code, name, status,
                    business_name, representative_name, business_reg_no,
                    address, phone, email,
                    mail_order_no, mail_order_exempt_reason, commission_bp)
values
    ('demo-fashion', '데모패션', 'active',
     '주식회사 데모패션', '김대표', '1234567891',
     '서울특별시 강남구 테헤란로 123', '02-1234-5678', 'contact@demo-fashion.example.com',
     '2026-서울강남-01234', null, 1000),

    -- 간이과세자라 통신판매업 신고가 면제된다. 번호가 없는 것이 미완성이 아니다.
    ('demo-craft', '데모공방', 'active',
     '데모공방', '이대표', '9876543215',
     '부산광역시 해운대구 센텀중앙로 45', '051-987-6543', 'contact@demo-craft.example.com',
     null, 'simplified_taxpayer', 1200);

-- 계정 여섯. 이메일 도메인은 example.com 이다(RFC 2606 예약).
-- 실존 도메인을 쓰면 언젠가 진짜 사람에게 메일이 간다.
insert into app_user (email, password_hash, display_name)
values
    ('admin@example.com',         '{bcrypt}' || crypt('demo-password-1234', gen_salt('bf', 10)), '데모관리자'),
    ('auditor@example.com',       '{bcrypt}' || crypt('demo-password-1234', gen_salt('bf', 10)), '데모감사자'),
    ('customer@example.com',      '{bcrypt}' || crypt('demo-password-1234', gen_salt('bf', 10)), '데모고객'),
    ('fashion-owner@example.com', '{bcrypt}' || crypt('demo-password-1234', gen_salt('bf', 10)), '패션사장'),
    ('craft-owner@example.com',   '{bcrypt}' || crypt('demo-password-1234', gen_salt('bf', 10)), '공방사장'),
    ('both-member@example.com',   '{bcrypt}' || crypt('demo-password-1234', gen_salt('bf', 10)), '양쪽소속');

-- 소속을 먼저 넣는다. V4 의 트리거가 "셀러 소속이 아닌 사용자에게 그 셀러의 역할" 을 막는다.
--
-- both-member 는 두 셀러에 다 속한다. 그런데 역할은 한쪽에서만 받는다 —
-- 그래야 "seller 스코프가 조직 부여면 받은 그 셀러만 덮는다" 를 데이터로 밟을 수 있다.
insert into seller_member (seller_id, user_id)
select s.seller_id, u.user_id
  from seller s
  join app_user u on true
 where (s.code = 'demo-fashion' and u.email in ('fashion-owner@example.com', 'both-member@example.com'))
    or (s.code = 'demo-craft'   and u.email in ('craft-owner@example.com',   'both-member@example.com'));

-- 전역 역할. 여섯 명 모두 고객이기도 하다 — 사장도 물건을 산다.
insert into user_role (user_id, role_id)
select u.user_id, r.role_id
  from app_user u
  join role r on r.code = 'customer'
 where u.email like '%@example.com';

insert into user_role (user_id, role_id)
select u.user_id, r.role_id
  from app_user u
  join role r on r.code = 'admin'
 where u.email = 'admin@example.com';

insert into user_role (user_id, role_id)
select u.user_id, r.role_id
  from app_user u
  join role r on r.code = 'auditor'
 where u.email = 'auditor@example.com';

-- 조직 역할. 반드시 셀러를 지정해야 한다(V4 트리거).
insert into user_role (user_id, role_id, seller_id)
select u.user_id, r.role_id, s.seller_id
  from app_user u
  join role r on r.code = 'seller_owner'
  join seller s on true
 where (u.email = 'fashion-owner@example.com' and s.code = 'demo-fashion')
    or (u.email = 'craft-owner@example.com'   and s.code = 'demo-craft')
    -- 양쪽에 속하지만 역할은 공방에서만 받는다. 패션 상품에는 권한이 없어야 맞다.
    or (u.email = 'both-member@example.com'   and s.code = 'demo-craft');

-- 동의. 가입 흐름을 안 타고 넣는 것이라 사건을 직접 적는다(5-0).
-- 필수 항목만 동의한 상태로 둔다 — 선택 항목은 건드린 적이 없어서 행이 없다.
insert into user_consent (user_id, consent_item_id, granted, source)
select u.user_id, ci.consent_item_id, true, 'signup'
  from app_user u
  join consent_item ci on ci.is_required
 where u.email like '%@example.com';
