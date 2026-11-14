-- 저작권 재개 요구 · 판단 · 예정일 재개 · 소 제기 통보(`Q465`, 저작권법 제103조③ · 시행령 제42·43조 `D2` R42).
--
-- **신고당 하나다** — 재개 요구는 그 중단에 대한 것이라 기본키가 신고 번호다. 복제·전송자 셀프 입구는 없고 수령인이 받은 요구를
-- 관리자가 기록한다 — 그 기록이 「받았다」의 증거다. **안 지운다**(신고와 같은 증거, `data-lifecycle.md`).
--
-- 기간은 법이 정했다. 예정일은 요구받은 날의 7일 이후 14일 이내(시행령 제43조) — 제약이 든다. 요구 기한 30일(제42조)은
-- 통보 발송 시각이 다른 표(`notification`)에 있어서 앱이 잰다.
--
-- 판단이 빈 줄에서 `decision = 'scheduled'` 는 null 이라 `check` 를 지나간다 — 그래서 `is not distinct from` 으로 쓴다.
create table copyright_reinstatement (
    copyright_report_id bigint not null primary key
        references copyright_report (copyright_report_id) on delete restrict,
    requested_at timestamptz not null,
    requester_claim text not null,
    decision text,
    decided_at timestamptz,
    decided_by_user_id bigint references app_user (user_id),
    scheduled_for date,
    suit_notified_at timestamptz,
    reinstated_at timestamptz,
    created_at timestamptz not null default now(),
    constraint copyright_reinstatement_decision_check
        check (decision is null or decision in ('scheduled', 'refused')),
    constraint copyright_reinstatement_decided_check
        check (num_nulls(decision, decided_at, decided_by_user_id) in (0, 3)),
    constraint copyright_reinstatement_scheduled_for_check
        check ((decision is not distinct from 'scheduled') = (scheduled_for is not null)),
    constraint copyright_reinstatement_window_check
        check (scheduled_for is null
               or scheduled_for between (requested_at at time zone 'Asia/Seoul')::date + 7
                                    and (requested_at at time zone 'Asia/Seoul')::date + 14),
    constraint copyright_reinstatement_reinstated_check
        check (reinstated_at is null
               or (decision is not distinct from 'scheduled' and suit_notified_at is null)),
    constraint copyright_reinstatement_requester_claim_length_check
        check (length(requester_claim) between 1 and 2000)
);

create index copyright_reinstatement_decided_by_user_idx
    on copyright_reinstatement (decided_by_user_id) where decided_by_user_id is not null;

-- 배치가 오늘 재개할 것을 고른다.
create index copyright_reinstatement_due_idx
    on copyright_reinstatement (scheduled_for)
 where decision = 'scheduled' and reinstated_at is null and suit_notified_at is null;

comment on table copyright_reinstatement is
    '저작권 게시 중단에 대한 재개 요구와 그 처리(Q465, 저작권법 제103조③, R42). 신고당 하나, 안 지운다';
comment on column copyright_reinstatement.requested_at is '수령인이 재개 요구를 받은 시각 — 30일 기한(시행령 제42조)과 예정일 범위의 기준';
comment on column copyright_reinstatement.requester_claim is '복제·전송자가 낸 정당한 권리의 소명(시행령 제42조)';
comment on column copyright_reinstatement.scheduled_for is '재개 예정일 — 요구받은 날의 7일 이후 14일 이내(시행령 제43조)';
comment on column copyright_reinstatement.suit_notified_at is '권리주장자가 소를 제기했다고 알려 온 시각 — 차면 재개하지 않는다(제103조③ 단서)';
comment on column copyright_reinstatement.reinstated_at is '예정일에 배치가 다시 게시한 시각';
