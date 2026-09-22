-- V82 를 되돌린다. **초대 이력이 전부 사라진다** — 누가 누구를 불러 들였는지의 근거라
-- 잘못 들어온 계정의 출처를 나중에 못 찾는다.
--
-- 되돌리기 전에 `scripts/db-dump.sh` 를 돌린다(`64`).

drop trigger if exists seller_invitation_role_check on seller_invitation;
drop function if exists check_seller_invitation_role();

drop index if exists seller_invitation_live_unique;
drop table if exists seller_invitation;

-- 담당자 역할을 거둔다. **부여를 먼저 지운다** — user_role.role_id 가 restrict 라
-- 사람이 달린 역할은 안 지워진다(`V4`).
delete from user_role
 where role_id in (select role_id from role where code = 'seller_staff');
delete from role_permission
 where role_id in (select role_id from role where code = 'seller_staff');
delete from role where code = 'seller_staff';

-- 멤버 권한도 같이 거둔다. 표가 없는데 동작만 남으면 아무 데도 안 걸린 권한이 둘 뜬다.
delete from role_permission
 where permission_id in (select permission_id from permission
                          where resource = 'seller_member');
delete from permission where resource = 'seller_member';
