-- 정산서를 지급 상태로 옮긴다(청크 21).
--
-- 19 가 지급액을 확정하는 데까지 왔고 그 뒤가 비어 있었다 — 마감된 정산서와 실제로 돈이
-- 나간 정산서가 **데이터로 안 갈렸다.** 두 번 지급해도 표가 아무 말을 안 한다.
--
-- **요청과 승인을 가른다.** refund 가 같은 것을 이미 했고(V23·V24) 이유도 같다 —
-- 돈이 나가는 결정을 한 사람이 혼자 끝내지 않는다.


-- 지급 상태.
--
--   pending    마감만 됐다. 아직 아무도 지급을 시작 안 했다
--   requested  누가 지급을 올렸다
--   paid       돈이 나갔다
--   rejected   반려됐다. 다시 올릴 수 있다
--
-- **반려가 종점이 아니다.** 다시 올리면 requested 로 돌아가고 처리 칸(payout_decided_*)이
-- 비워진다 — 정산서는 (셀러, 주기) 당 하나라 환불처럼 새 행을 만들 수가 없다.
-- 반려했다는 사실은 감사 로그가 든다.
--
-- **취소가 없다.** 지급액은 마감이 정한 값이라 반려한다고 사라지지 않는다.
alter table settlement add column payout_status text not null default 'pending';

alter table settlement add column payout_requested_by_user_id bigint
    references app_user (user_id) on delete restrict;
alter table settlement add column payout_requested_at timestamptz;

alter table settlement add column payout_decided_by_user_id bigint
    references app_user (user_id) on delete restrict;
alter table settlement add column payout_decided_at timestamptz;

alter table settlement add constraint settlement_payout_status_check
    check (payout_status in ('pending', 'requested', 'paid', 'rejected'));

-- 요청한 정산서에는 요청자와 시각이 있고, 안 한 것에는 없다.
--
-- **셋을 사슬로 묶는다.** 「상태 = (사람과 시각이 둘 다 있나)」로 쓰면 한쪽만 채운 행이
-- 통과한다 — 한쪽이 비어 오른쪽이 거짓이 되고 상태도 거짓이라 등식이 맞아 버린다
-- (V53 의 inquiry_block_check 에서 오늘 같은 구멍을 밟았다).
alter table settlement add constraint settlement_payout_request_check
    check ((payout_status = 'pending') = (payout_requested_at is null)
           and (payout_requested_at is null) = (payout_requested_by_user_id is null));

-- 처리된 정산서에는 처리자와 시각이 있고, 그 밖에는 없다.
alter table settlement add constraint settlement_payout_decision_check
    check ((payout_status in ('paid', 'rejected')) = (payout_decided_at is not null)
           and (payout_decided_at is null) = (payout_decided_by_user_id is null));

-- **자기가 올린 지급은 자기가 승인 못 한다.**
--
-- 앱에도 같은 검사가 있지만 그건 강제 지점 3위라 새 입구가 생기면 빠뜨린다(D23 축 2).
-- 여기 있으면 psql 로 넣어도 걸린다 — refund_self_approval_check 와 같은 두 겹이다.
--
-- 돈이 우리에게서 나가는 자리라 요청·승인이 둘 다 관리자다. 셀러가 올리게 하면
-- **셀러가 안 눌러서 지급이 밀리는 구조**가 되는데, 그건 12a-5 에서 오늘 고친 함정이다.
alter table settlement add constraint settlement_payout_self_approval_check
    check (payout_decided_by_user_id is null
           or payout_requested_by_user_id is null
           or payout_decided_by_user_id <> payout_requested_by_user_id);

-- **줄 돈이 없으면 지급을 못 올린다.**
--
-- 지급액이 0 이하인 정산서는 이월로 넘어가지 지급 대상이 아니다(business-model.md).
-- 앱 조건으로만 두면 새 입구가 음수를 송금하는 자리가 생긴다.
alter table settlement add constraint settlement_payout_amount_check
    check (payout_status = 'pending' or payout_amount > 0);

comment on column settlement.payout_status is
    '지급 진행 상태. 마감(19)과 지급(21)은 다른 사건이다 — 확정됐다고 돈이 나간 것이 아니다';

-- 지급일이 지났는데 안 나간 것을 찾는 자리.
--
-- **「배치가 돌았나」가 아니라 「밀린 것이 몇이냐」를 봐야 값이 보인다**(12a-5·36a 와 같은 판단).
create index settlement_unpaid_idx on settlement (settlement_cycle_id)
 where payout_status <> 'paid';


-- 권한.
--
-- 요청과 승인을 다른 동작으로 가른다. 하나로 두면 올리는 순간 승인이 같이 열려서
-- 자기승인 제약만 남는데, 그 제약은 **자기 것만** 막는다(V24 가 환불에서 적은 문장 그대로).
insert into permission (resource, action, description) values
    ('settlement', 'request_payout', '확정된 정산의 지급을 올린다'),
    ('settlement', 'payout',         '올라온 지급을 승인하거나 반려한다');

insert into role_permission (role_id, permission_id, scope, effect)
select r.role_id, p.permission_id, 'all', 'allow'
  from role r
  join permission p on p.resource = 'settlement'
                   and p.action in ('request_payout', 'payout')
 where r.code = 'admin';

-- 감사자. 읽기가 아니면 막는다.
--
-- V5 의 deny 도 그 시점의 permission 만 훑었다. V12 의 주석이 「새 권한을 넣는 마이그레이션이
-- 감사자 deny 도 같이 넣어야 한다」고 남긴 자리다.
insert into role_permission (role_id, permission_id, scope, effect)
select r.role_id, p.permission_id, 'all', 'deny'
  from role r
  join permission p on p.resource = 'settlement'
                   and p.action in ('request_payout', 'payout')
 where r.code = 'auditor';


-- 지급이 관리자 밖으로 안 나갔는지 확인한다.
--
-- 빠뜨리면 조용하다. 셀러에게 payout 을 주면 **셀러가 자기 지급을 스스로 승인**하는데,
-- 자기승인 제약은 요청자와 승인자가 같을 때만 막아서 둘이 짜면 통과한다.
do $$
declare granted text;
begin
    select string_agg(r.code || ':' || p.action, ', ' order by r.code || ':' || p.action)
      into granted
      from role_permission rp
      join role r on r.role_id = rp.role_id
      join permission p on p.permission_id = rp.permission_id
     where p.resource = 'settlement' and p.action in ('request_payout', 'payout')
       and rp.effect = 'allow' and r.code <> 'admin';

    if granted is not null then
        raise exception '지급은 관리자만이다. 열린 것: %', granted;
    end if;
end $$;


do $$
declare missing int;
begin
    select count(*) into missing
      from permission p
     where p.resource = 'settlement'
       and p.action in ('request_payout', 'payout')
       and not exists (
           select 1
             from role_permission rp
             join role r on r.role_id = rp.role_id
            where rp.permission_id = p.permission_id
              and r.code = 'auditor' and rp.effect = 'deny');

    if missing > 0 then
        raise exception '감사자 거부가 안 걸린 정산 동작이 % 개 있다', missing;
    end if;
end $$;
