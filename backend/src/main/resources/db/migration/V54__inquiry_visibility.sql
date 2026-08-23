-- 문의의 공개 여부와 권한(청크 59).
--
-- 58 이 표만 세웠다. 입구가 없어서 R25(불만·분쟁)와 R28(처리정지·이의제기)의 법정 창구가
-- 아직 방침 문구고, 「어디로 받나」에 코드가 답을 못 한다.


-- 1. 공개 여부.
--
-- **종류가 반쯤 정한다.** 계정에 붙는 요구 셋은 언제나 비공개여야 한다 —
-- 처리정지 요구가 상품 페이지에 뜨면 그 사람이 무슨 요구를 했는지가 남에게 보인다.
-- 상품 Q&A 만 작성자가 고른다.
--
-- **기본값이 비공개다. API 기본값과 반대다.**
--
-- 상품 Q&A 는 남이 읽는 것이 쓸모의 절반이라 입구는 안 보내면 공개로 본다.
-- 그런데 그 판단을 컬럼 기본값으로 내리면 **is_public 을 안 쓴 insert 가 전부 공개가 된다** —
-- 계정에 붙는 요구를 그냥 넣으면 아래 check 에 걸려서 실패하고, 그때 고치는 사람이
-- 「true 로 넣으면 되나」를 먼저 시도하게 된다.
--
-- 층마다 기본값이 달라도 된다. **아래층은 안전한 쪽으로, 위층은 쓰기 좋은 쪽으로** 둔다 —
-- 빠뜨렸을 때 새는 것이 아니라 안 보이는 쪽으로 떨어져야 한다.
alter table inquiry add column is_public boolean not null default false;

-- 계정에 붙는 요구는 공개될 수 없다. **앱 검증으로 두면 새 입구가 빠뜨린다**(D23 축 2).
alter table inquiry add constraint inquiry_visibility_check
    check (kind = 'product' or is_public = false);

comment on column inquiry.is_public is
    '남에게 보이나. 상품 Q&A 만 고를 수 있고 계정에 붙는 요구는 언제나 false 다(청크 59)';

-- 상품 화면이 공개분만 최신순으로 훑는다. 부분 인덱스의 조건이 곧 그 목록의 조건이다.
--
-- 내린 게시물은 빠진다(R34, 정보통신망법 제50조의7) — 거부하면 게시가 중단돼야 하는데
-- 조건에서 빼지 않으면 화면에만 안 보이고 목록 API 로는 나간다.
create index inquiry_public_idx on inquiry (product_id, created_at desc)
 where is_public and status <> 'blocked' and product_id is not null;


-- 2. 권한.
--
-- 자원을 새로 판다. 문의는 상품에도 계정에도 붙는데 어느 쪽 자원으로도 안 접힌다 —
-- product:read 에 얹으면 계정에 붙는 요구가 상품 권한으로 열리고,
-- 그 요구는 상품을 안 산 사람도 낸다.
insert into permission (resource, action, description) values
    ('inquiry', 'create', '상품에 묻거나 법정 요구를 접수한다'),
    ('inquiry', 'read',   '문의와 답변을 조회한다'),
    ('inquiry', 'answer', '문의에 답한다');


-- 고객. 자기가 낸 것만 보고, 낸다.
--
-- 공개 목록은 이 권한을 안 지난다 — 비로그인도 보는 자리라 물을 사람이 없다.
-- 판정을 지나는 것은 「내 문의」와 「단건 조회」뿐이다.
insert into role_permission (role_id, permission_id, scope, effect)
select r.role_id, p.permission_id, 'own', 'allow'
  from role r
  join permission p on p.resource = 'inquiry' and p.action in ('create', 'read')
 where r.code = 'customer';


-- 판매자(대표). 자기 셀러 상품에 달린 것만 보고 답한다.
--
-- create 는 안 준다. 셀러가 자기 상품에 묻는 자리가 없고,
-- 열어 두면 셀러가 자문자답한 글이 공개 Q&A 에 쌓인다.
insert into role_permission (role_id, permission_id, scope, effect)
select r.role_id, p.permission_id, 'seller', 'allow'
  from role r
  join permission p on p.resource = 'inquiry' and p.action in ('read', 'answer')
 where r.code = 'seller_owner';


-- 관리자. V3 의 admin 부여는 그 시점의 permission 만 훑었으므로 새 권한은 여기서 넣는다.
--
-- **법정 요구에 답하는 것이 우리 몫이라 answer 가 필요하다**(R25·R28) —
-- 처리정지와 이의제기는 개인정보처리자인 우리에게 온 것이라 셀러가 답할 것이 아니다.
insert into role_permission (role_id, permission_id, scope, effect)
select r.role_id, p.permission_id, 'all', 'allow'
  from role r
  join permission p on p.resource = 'inquiry'
 where r.code = 'admin';


-- 감사자. 읽기는 열고 나머지는 막는다.
--
-- V5 의 deny 도 그 시점의 permission 만 훑었다. V12 의 주석이 「새 권한을 넣는 마이그레이션이
-- 감사자 deny 도 같이 넣어야 한다」고 남긴 자리다.
insert into role_permission (role_id, permission_id, scope, effect)
select r.role_id, p.permission_id, 'all', 'allow'
  from role r
  join permission p on p.resource = 'inquiry' and p.action = 'read'
 where r.code = 'auditor';

insert into role_permission (role_id, permission_id, scope, effect)
select r.role_id, p.permission_id, 'all', 'deny'
  from role r
  join permission p on p.resource = 'inquiry' and p.action in ('create', 'answer')
 where r.code = 'auditor';


-- 감사자 거부가 실제로 걸렸는지 확인한다.
--
-- 빠뜨렸을 때 아무 일도 안 일어나기 때문에 둔다 — 감사자가 남의 문의에 답할 수 있게 되는데
-- 오류도 로그도 안 남는다(V24 와 같은 자리).
do $$
declare missing int;
begin
    select count(*) into missing
      from permission p
     where p.resource = 'inquiry'
       and p.action in ('create', 'answer')
       and not exists (
           select 1
             from role_permission rp
             join role r on r.role_id = rp.role_id
            where rp.permission_id = p.permission_id
              and r.code = 'auditor' and rp.effect = 'deny');

    if missing > 0 then
        raise exception '감사자 거부가 안 걸린 문의 동작이 % 개 있다', missing;
    end if;
end $$;


-- 셀러가 남의 문의를 못 보는지 확인한다.
--
-- seller 스코프가 아닌 부여가 seller_owner 에게 들어가면 자기 상품이 아닌 문의까지 열린다.
-- 비공개 문의에는 연락처가 섞여 들어오므로(D16) 그 순간 개인정보가 셀러에게 샌다.
do $$
declare wide text;
begin
    select string_agg(p.action, ', ' order by p.action) into wide
      from role_permission rp
      join role r on r.role_id = rp.role_id
      join permission p on p.permission_id = rp.permission_id
     where p.resource = 'inquiry' and r.code = 'seller_owner'
       and rp.effect = 'allow' and rp.scope <> 'seller';

    if wide is not null then
        raise exception '셀러에게 seller 아닌 범위로 열린 문의 동작이 있다: %', wide;
    end if;
end $$;
