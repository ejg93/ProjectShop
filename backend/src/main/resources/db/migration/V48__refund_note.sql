-- 환불 사유 글을 5년 표에서 뺀다(청크 5i-2, `D2` R9).
--
-- **컬럼 자체는 사람 정보가 아닌데 글 안에 섞여 들어온다.** 「집 앞에 두세요, 010-…」 같은 것이고,
-- 특히 `request_reason` 은 **구매자가 직접 쓰는 유일한 칸**이다.
--
-- 개인정보보호법 제21조제3항이 보존분을 분리 저장하라고 하는데 이 둘은 5년 표 안에 있었다.
-- `order_shipping`·`payment_card`·`notification_body` 가 쓴 수단을 그대로 쓴다 — **표를 가른다.**
--
-- **`reason_code` 는 안 옮긴다.** 그쪽은 `check` 로 닫힌 열거값이라 사람 글이 안 들어오고,
-- 「무슨 사유로 몇 건」은 그 값이 답한다. 5년치 분석이 안 깨지는 이유가 이것이다.

create table refund_note (
    -- 1:1 이라 본체의 기본키를 그대로 쓴다(`D22`). 대리키를 얹으면 "환불당 하나" 를
    -- 유니크로 따로 적게 된다 — `order_shipping` 과 같은 모양이다.
    refund_id bigint not null primary key references refund (refund_id) on delete cascade,

    -- 구매자가 쓴 요청 사유.
    request_reason text,

    -- 관리자가 쓴 승인·반려 사유.
    decision_reason text,

    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),

    constraint refund_note_request_reason_length_check
        check (length(request_reason) between 1 and 500),

    constraint refund_note_decision_reason_length_check
        check (length(decision_reason) between 1 and 500),

    -- 둘 다 비면 행이 있을 이유가 없다. 빈 행이 쌓이면 파기가 셀 대상만 늘어난다.
    constraint refund_note_any_check
        check (num_nonnulls(request_reason, decision_reason) >= 1)
);

comment on table refund_note is
    '환불 사유 글. 사람이 쓴 글이라 5년 표에서 뺐다. 거래 종료 + 6개월에 사라진다(D13, 5i-2)';

insert into refund_note (refund_id, request_reason, decision_reason, created_at)
select refund_id, request_reason, decision_reason, created_at
  from refund
 where request_reason is not null or decision_reason is not null;

-- **반려에는 사유가 필요하다.** 컬럼이 옮겨 가면서 `refund_rejection_reason_check` 가
-- 한 행 안에서 안 끝나게 됐다 — 표를 넘는 조건이라 `check` 로 못 건다.
--
-- 지연 제약 트리거로 내린다. 커밋 시점에 보므로 <b>사유를 나중에 넣어도 된다</b> —
-- 서비스가 `refund` 를 먼저 고치고 글을 뒤에 넣는 순서를 그대로 쓸 수 있다.
--
-- **`refund_note` 에는 안 건다.** 파기 배치가 여섯 달 뒤 그 행을 지우는데,
-- 거기까지 보면 파기가 이 제약에 막힌다 — 검사할 시점은 반려하는 순간이지 그 뒤가 아니다.
create or replace function assert_rejection_reason() returns trigger as $$
begin
    if new.status = 'rejected'
       and not exists (select 1 from refund_note n
                        where n.refund_id = new.refund_id
                          and n.decision_reason is not null) then
        raise exception '반려에는 사유가 필요하다 (refund_id=%)', new.refund_id
            using errcode = 'check_violation';
    end if;
    return null;
end;
$$ language plpgsql;

create constraint trigger refund_requires_rejection_reason
    after insert or update of status on refund
    deferrable initially deferred
    for each row execute function assert_rejection_reason();

alter table refund drop constraint refund_rejection_reason_check;
alter table refund drop constraint refund_request_reason_length_check;
alter table refund drop constraint refund_decision_reason_length_check;

alter table refund drop column request_reason;
alter table refund drop column decision_reason;
