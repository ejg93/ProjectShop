-- V93 을 되돌린다. **마지막 대표를 지키는 것이 다시 앱 검사뿐이 된다** — 동시에 서로를 내보내는
-- 대표 둘과 마지막 대표의 탈퇴가 다시 셀러를 잠근다.
--
-- 되돌리기 전에 `scripts/db-dump.sh` 를 돌린다(`64`).

drop trigger app_user_withdrawal_keeps_seller_owner on app_user;
drop function check_withdrawal_keeps_owner();

drop trigger user_role_keeps_seller_owner on user_role;
drop function check_user_role_keeps_owner();

drop function assert_seller_keeps_owner(bigint);
