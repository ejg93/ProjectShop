-- 시스템이 낸 요청은 시스템이 승인한다(청크 12a-5, `D2` R5).
--
-- **승인 대기가 법정 기한을 먹고 있었다.** `RefundSweeper` 가 닫힌 묶음에 요청을 만드는데
-- (`12a-3`) 승인은 사람만 할 수 있었다. `due_at` 은 요청이 만들어진 시각이 아니라
-- **사건이 일어난 날**에서 세므로(제18조제2항), 사람이 안 누르는 동안 3영업일이 그냥 흐르고
-- `12a-4` 가 우리에게 연 15%를 물린다(시행령 제21조의3).
--
-- `10a-2` 가 그 입구를 하나 더 열었다 — 방치된 묶음을 자동으로 취소하니 요청이 사람 손 없이 는다.
--
-- **법은 요청·승인 2단계를 요구하지 않는다.** 제18조제2항은 3영업일만 본다.
-- 대기라는 상태는 우리가 만든 것이고(`12a-1`), 법은 그 대기를 기한에서 빼 주지 않는다.
--
-- 시스템이 만든 요청은 **전량 환불**이라 사람이 판단할 여지가 애초에 없다
-- (`RefundSweeper` 가 항목을 안 고른다). 판단할 것이 없는 자리에 사람을 세워 두면
-- 그 자리가 곧 지연이 된다.


-- 승인자의 종류. requested_by_type 과 같은 모양이다(`V25`).
--
-- **null 은 「아직 안 정해졌다」다.** requested_by_type 은 not null 인데 이쪽이 nullable 인 것은
-- 요청은 행이 생길 때 이미 누가 냈고, 승인은 나중에 오기 때문이다.
alter table refund add column approved_by_type text;

-- 지금 처리된 행은 전부 사람이 승인·반려한 것이다. 승인은 관리자 하나뿐이고
-- (`V24` 의 do 블록이 그것을 지킨다) 반려도 같은 입구다.
update refund set approved_by_type = 'admin' where status <> 'requested';

-- 값이 둘뿐이다. requested_by_type 의 넷을 그대로 안 쓴다 —
-- **승인은 관리자와 시스템만 한다.** 목록을 넓게 열면 `V24` 가 지키는 「승인은 관리자만」이
-- 이 표의 값으로는 뚫려 있게 되고, 그때 어느 쪽이 진짜인지가 안 갈린다.
alter table refund add constraint refund_approved_by_type_check
    check (approved_by_type is null or approved_by_type in ('admin', 'system'));

-- 처리된 요청에는 처리자와 시각이 있고, 안 처리된 요청에는 없다.
--
-- **원래는 approved_by_user_id 를 봤다**(`V23`). 시스템 승인은 지목할 사람이 없어서
-- 그 컬럼이 null 인데, 그러면 이 제약이 「아직 안 처리됐다」로 읽는다.
-- 사람이 아니라 **종류**를 보게 바꾼다 — 시스템도 처리자다.
alter table refund drop constraint refund_decision_check;

alter table refund add constraint refund_decision_check
    check ((status = 'requested') = (approved_by_type is null and decided_at is null));

-- 사람이 처리한 것은 누구인지 남는다. 시스템은 지목할 사람이 없다.
-- 한쪽만 걸면 「관리자가 승인했는데 누구인지 모르는」 행이 생긴다(`V25` 와 같은 문장).
alter table refund add constraint refund_approved_by_user_check
    check ((approved_by_type is null and approved_by_user_id is null)
           or (approved_by_type = 'system') = (approved_by_user_id is null));

-- **시스템은 자기가 만든 요청만 승인한다.**
--
-- 사람이 낸 요청은 부분 환불일 수 있고 사유도 자유 텍스트라 판단이 남아 있다.
-- 그것까지 배치가 승인하면 「검토」라는 단계가 이름만 남는다.
--
-- 앱에서 고르는 조건으로만 두면 새 입구가 생길 때 빠뜨린다(`D23` 축 2) —
-- 조건이 아니라 **불가능**이어야 한다.
alter table refund add constraint refund_system_approval_scope_check
    check (approved_by_type <> 'system' or requested_by_type = 'system');

comment on column refund.approved_by_type is
    '처리 출처. system 은 스위퍼가 자기 요청을 승인한 것이다(12a-5). 미처리면 null';
