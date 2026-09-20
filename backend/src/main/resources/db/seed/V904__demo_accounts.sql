-- 사용자 그룹마다 데모 계정 셋(`Q130`).
--
-- **하나로는 둘이 같이 못 본다.** 세션이 계정마다 하나라, 배포한 사이트를 두 사람이 같은
-- 계정으로 열면 뒤에 들어온 쪽이 앞을 밀어낸다. 그룹이 셋이므로 아홉이다.
--
-- **`V900` 의 여섯과 목적이 다르다.** 그쪽은 권한 축을 데이터로 밟으려고 세운 것이라
-- 「양쪽에 속하는데 역할은 한쪽만」 같은 모서리를 든다. 이쪽은 **누구나 눌러 보라고
-- 공개할 것**이라 이름이 규칙적이어야 하고, 셋이 서로 구별되는 것 말고는 특별할 것이 없다.
--
-- **`V900`~`V903` 을 직접 안 고친다**(`Q51`). 배포 기준점 뒤라 체크섬이 남의 DB 에 박혀 있다.
--
-- **재고 올리는 `V905` 보다 앞이다.** `SeedOutboxTest` 가 **마지막 시드**에 아웃박스를 비우는
-- 문장이 있는지 보는데, 계정을 넣는 이 파일은 사건을 안 낳아서 비울 것이 없다.
-- 사건을 낳는 쪽을 뒤에 두고 그쪽이 걷는다.
--
--
-- **공개되는 것이 무엇인지 적어 둔다.** 이 아홉은 로그인 화면에 그대로 실린다(`Q131`).
-- 시스템관리자 셋은 **방문자 누구나 관리자 화면을 연다**는 뜻이고, 그것이 이 데이터를
-- 세운 목적이다 — 연습용 DB 에 연습용 데이터만 있으므로 잃을 것이 없다.
-- 진짜 데이터가 들어오는 날에는 **이 파일이 먼저 나가야 한다.**
--
-- 비밀번호는 전부 `demo-password-1234` 다. `V900` 과 같은 값이라 안내가 한 줄로 끝나고,
-- 앱 규칙(`D14` — 15자 이상 ASCII)도 지킨다.
--
-- 이메일 도메인은 `example.com` 이다(RFC 2606 예약). 실존 도메인을 쓰면 언젠가 진짜 사람에게 간다.


-- 아홉.
--
-- **`display_name` 이 화면에 뜨는 이름이다.** 그룹과 번호를 그대로 적는다 — 셋 중 어느
-- 것으로 들어왔는지가 셸에 바로 보여야, 두 사람이 같이 볼 때 서로를 안 헷갈린다.
insert into app_user (email, password_hash, display_name)
select v.email, '{bcrypt}' || crypt('demo-password-1234', gen_salt('bf', 10)), v.display_name
  from (values
            ('buyer1@example.com',  '구매자1'),
            ('buyer2@example.com',  '구매자2'),
            ('buyer3@example.com',  '구매자3'),
            ('seller1@example.com', '판매자1'),
            ('seller2@example.com', '판매자2'),
            ('seller3@example.com', '판매자3'),
            ('admin1@example.com',  '시스템관리자1'),
            ('admin2@example.com',  '시스템관리자2'),
            ('admin3@example.com',  '시스템관리자3')
       ) as v (email, display_name)
 where not exists (select 1 from app_user u where lower(u.email) = v.email);


-- 소속을 먼저 넣는다. `V4` 의 트리거가 「셀러 소속이 아닌 사용자에게 그 셀러의 역할」을 막는다.
--
-- **둘을 패션에, 하나를 공방에 붙인다.** 셋을 한 셀러에 몰면 **셀러가 둘이라는 것이
-- 화면에서 안 보인다** — 상품 목록도 받은 주문도 소속 셀러 것만 나오기 때문이다.
insert into seller_member (seller_id, user_id)
select s.seller_id, u.user_id
  from app_user u
  join seller s on s.code = case
                                when lower(u.email) = 'seller3@example.com' then 'demo-craft'
                                else 'demo-fashion'
                            end
 where lower(u.email) in ('seller1@example.com', 'seller2@example.com', 'seller3@example.com')
   and not exists (select 1 from seller_member sm
                    where sm.seller_id = s.seller_id and sm.user_id = u.user_id);


-- 전역 역할. **아홉 다 고객이다** — 판매자도 관리자도 물건을 산다(`V900` 과 같은 판단).
insert into user_role (user_id, role_id)
select u.user_id, r.role_id
  from app_user u
  join role r on r.code = 'customer'
 where lower(u.email) in ('buyer1@example.com', 'buyer2@example.com', 'buyer3@example.com',
                          'seller1@example.com', 'seller2@example.com', 'seller3@example.com',
                          'admin1@example.com', 'admin2@example.com', 'admin3@example.com')
   and not exists (select 1 from user_role ur
                    where ur.user_id = u.user_id and ur.role_id = r.role_id);

insert into user_role (user_id, role_id)
select u.user_id, r.role_id
  from app_user u
  join role r on r.code = 'admin'
 where lower(u.email) in ('admin1@example.com', 'admin2@example.com', 'admin3@example.com')
   and not exists (select 1 from user_role ur
                    where ur.user_id = u.user_id and ur.role_id = r.role_id);


-- 조직 역할. 반드시 셀러를 지정해야 한다(`V4` 트리거).
insert into user_role (user_id, role_id, seller_id)
select u.user_id, r.role_id, sm.seller_id
  from app_user u
  join seller_member sm on sm.user_id = u.user_id
  join role r on r.code = 'seller_owner'
 where lower(u.email) in ('seller1@example.com', 'seller2@example.com', 'seller3@example.com')
   and not exists (select 1 from user_role ur
                    where ur.user_id = u.user_id and ur.role_id = r.role_id
                      and ur.seller_id = sm.seller_id);


-- 필수 동의. 가입 흐름을 안 타고 넣는 것이라 사건을 직접 적는다(`5-0`).
--
-- **시행된 판만 고른다.** `V900` 이 판을 안 가려서 `V902` 가 뒤에서 걷어내야 했다 —
-- 아직 시행 안 된 개정판에 동의한 것으로 기록되기 때문이다(`V27` 불변 트리거·`D2-7`).
-- 운영 경로 둘(`SignupService.currentConsentItems`·`ConsentService.findItem`)이 보는 조건과 같다.
insert into user_consent (user_id, consent_item_id, granted, source)
select u.user_id, ci.consent_item_id, true, 'signup'
  from app_user u
  join consent_item ci on ci.is_required and ci.effective_at <= now()
 where lower(u.email) in ('buyer1@example.com', 'buyer2@example.com', 'buyer3@example.com',
                          'seller1@example.com', 'seller2@example.com', 'seller3@example.com',
                          'admin1@example.com', 'admin2@example.com', 'admin3@example.com')
   and not exists (select 1 from user_consent uc
                    where uc.user_id = u.user_id and uc.consent_item_id = ci.consent_item_id);
