-- 전이 사유 글을 5년 표에서 뺀다(청크 5i-3, `D2` R9).
--
-- `5i-2` 가 환불 사유 둘을 옮긴 것과 같은 일이고, **5년 표에 남은 마지막 자유 텍스트**다.
-- 쓰는 사람이 관리자·셀러라 구매자가 쓰는 칸보다 위험이 한 단계 낮지만,
-- <b>글에 사람 정보가 섞이는 것은 같다</b> — 「고객이 010-… 로 연락 와서 취소」 같은 것이다.
--
-- **읽는 자리가 없다.** 화면도 조회도 이 칸을 안 쓴다 — 남기는 목적이 분쟁 때 꺼내 보는 것이라
-- <b>평소에 아무도 안 보는데 5년을 산다</b>. 옮기기에 제일 좋은 모양이다.

create table order_status_history_note (
    -- 1:1 이라 본체의 기본키를 그대로 쓴다(`D22`).
    order_status_history_id bigint not null primary key
        references order_status_history (order_status_history_id) on delete cascade,

    reason text not null,

    created_at timestamptz not null default now(),

    constraint order_status_history_note_reason_length_check
        check (length(reason) between 1 and 500)
);

comment on table order_status_history_note is
    '전이 사유 글. 사람이 쓴 글이라 5년 표에서 뺐다. 거래 종료 + 6개월에 사라진다(D13, 5i-3)';

insert into order_status_history_note (order_status_history_id, reason, created_at)
select order_status_history_id, reason, occurred_at
  from order_status_history
 where reason is not null;

-- **관리자의 강제 전이에는 사유가 필요하다.** 컬럼이 옮겨 가면서
-- `order_status_history_admin_reason_check` 가 한 행 안에서 안 끝나게 됐다 — `5i-2` 와 같은 자리다.
--
-- 지연 제약 트리거로 내린다. 커밋 시점에 보므로 이력을 먼저 남기고 사유를 뒤에 넣어도 된다.
--
-- **`order_status_history_note` 에는 안 건다.** 파기가 여섯 달 뒤 그 행을 지우는데
-- 거기까지 보면 파기가 막힌다 — 검사할 시점은 전이하는 순간이지 그 뒤가 아니다.
create or replace function assert_admin_transition_reason() returns trigger as $$
begin
    if new.actor_type = 'admin'
       and not exists (select 1 from order_status_history_note n
                        where n.order_status_history_id = new.order_status_history_id) then
        raise exception '관리자 전이에는 사유가 필요하다 (order_status_history_id=%)',
            new.order_status_history_id
            using errcode = 'check_violation';
    end if;
    return null;
end;
$$ language plpgsql;

create constraint trigger order_status_history_requires_admin_reason
    after insert on order_status_history
    deferrable initially deferred
    for each row execute function assert_admin_transition_reason();

alter table order_status_history drop constraint order_status_history_admin_reason_check;
alter table order_status_history drop column reason;
