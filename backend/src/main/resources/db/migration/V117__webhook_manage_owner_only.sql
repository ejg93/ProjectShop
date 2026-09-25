-- `webhook:manage` 는 대표(`seller_owner`)의 셀러 범위에만 열린다 — 적용 뒤에도 매번 막는다(`Q211`, PR #84 리뷰 봇).
--
-- `V114` 의 `do` 블록은 적용 순간에 한 번만 돈다. 뒤의 마이그레이션이 `V3` 관례(관리자는 전부 `all`)대로 새 권한을 관리자에게
-- 뿌리다가 `manage` 를 다시 주면 부여 시험(`WebhookEndpointServiceTest`)이 배포 뒤에야 빨갛다. 시크릿을 만드는 권한이라
-- 공개된 관리자 연습 계정이 가지면 누구나 남의 셀러 사건을 바깥으로 흘린다 — 축 2 에서 제약이 시험보다 위라 표로 내린다.
-- `V75` 의 `deny_write_to_read_only_roles` 가 같은 모양(역할×권한 조합을 트리거가 영구히 본다)이다. 거부(`deny`) 행은 안 본다.
create or replace function assert_webhook_manage_owner_only() returns trigger as $$
begin
    if new.effect = 'allow'
       and exists (select 1 from permission p
                    where p.permission_id = new.permission_id
                      and p.resource = 'webhook' and p.action = 'manage')
       and not (new.scope = 'seller'
                and exists (select 1 from role r where r.role_id = new.role_id and r.code = 'seller_owner')) then
        raise exception '웹훅 manage 는 대표에게 셀러 범위로만 준다 (role_id=%, scope=%)', new.role_id, new.scope
            using errcode = 'check_violation';
    end if;
    return new;
end;
$$ language plpgsql;

create trigger role_permission_webhook_manage_owner_only
    before insert or update on role_permission
    for each row execute function assert_webhook_manage_owner_only();
