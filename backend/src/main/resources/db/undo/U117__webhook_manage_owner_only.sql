-- V117 을 되돌린다. 트리거와 함수만 걷는다 — 부여 행은 안 건드린다.
drop trigger role_permission_webhook_manage_owner_only on role_permission;
drop function assert_webhook_manage_owner_only();
