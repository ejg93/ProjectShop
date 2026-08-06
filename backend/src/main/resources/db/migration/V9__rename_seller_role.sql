-- 역할 코드 'seller' 를 'seller_owner' 로 바꾼다.
--
-- 'seller' 가 세 자리에서 다른 뜻으로 쓰였다.
--   seller 테이블                       상품을 파는 조직
--   role.code = 'seller'                그 조직에서 일하는 사람의 역할
--   role_permission.scope = 'seller'    판정 범위
--
-- 조직과 역할이 같은 이름이면 "seller 를 지운다" 가 폐업인지 역할 회수인지 안 갈린다.
-- 'seller' 는 조직에만 남기고 역할에서 뺀다. scope 값은 조직 범위라는 뜻이라 그대로 둔다.
-- 근거는 doc/reference/glossary.md 에 있다.
--
-- role_permission·user_role 은 role_id 로 걸려 있어서 따라 고칠 것이 없다.
-- V4 의 트리거도 role.is_org_role 을 보므로 코드 이름과 무관하다.

update role
set code        = 'seller_owner',
    name        = '셀러 대표',
    description = '셀러의 상품을 등록하고 그 셀러의 주문을 처리한다'
where code = 'seller';

-- 실무자 역할 seller_staff 는 여기서 만들지 않는다.
-- 권한을 무엇까지 줄지가 청크 5a(조직 초대·멤버십)에서 정해진다.
-- 권한 없는 역할을 미리 넣으면 판정 테스트가 빈 역할을 물고 돌아간다.
