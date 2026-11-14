-- 저작권 게시 중단·재개 통보(`Q463`, 저작권법 제103조②·③ · 시행령 제41조 `D2` R42).
--
-- **새 알림 표를 안 만든다.** 발송 이력·본문·스위퍼·보유 기한(`D13`)이 `notification` 에 이미 있다 — 표를 하나 더 두면
-- 그 넷을 다 복제한다. 대신 둘을 넓힌다.
--
-- ① 대상 칸 `copyright_report_id` — 어느 신고에 대한 통보인지가 붙어야 「3일 이내」(시행령 제41조)를 잰다.
-- ② `user_id` 를 비울 수 있게 — **권리주장자는 우리 계정이 아니다**(신고를 로그인 없이 받는다, `Q94`). 주소는
--    `copyright_report.reporter_email` 에서 온다. 그 문을 신고자 사건 둘에만 연다(`notification_recipient_check`) —
--    다른 사건이 계정 없는 행을 쓰면 「누구에게 보냈나」에 못 답한다.
alter table notification
    add column copyright_report_id bigint references copyright_report (copyright_report_id) on delete restrict;

alter table notification alter column user_id drop not null;

-- 대상 칸이 다섯이 됐다(`V45` 가 넷으로 넓힌 것을 다시 건다). 「많아야 하나」는 그대로다.
alter table notification drop constraint notification_target_check;

alter table notification add constraint notification_target_check
    check (num_nonnulls(order_id, seller_order_id, refund_id, user_consent_id, copyright_report_id) <= 1);

alter table notification add constraint notification_recipient_check
    check (user_id is not null
           or event_type in ('copyright_taken_down_reporter', 'copyright_reinstatement_reporter'));

-- 사건 목록을 연다(`V46` 의 아홉 + 넷).
alter table notification drop constraint notification_event_type_check;

alter table notification add constraint notification_event_type_check
    check (event_type in ('order_placed', 'payment_completed', 'supply_delayed',
                          'refund_completed', 'password_reset', 'email_change',
                          'advertisement', 'consent_result', 'consent_reconfirm',
                          'copyright_taken_down_reporter', 'copyright_taken_down_uploader',
                          'copyright_reinstatement_reporter', 'copyright_reinstated_uploader'));

-- 같은 신고의 같은 사건은 받는 사람마다 하나다. 신고자는 계정이 없어 사건·신고 짝으로, 복제·전송자는 계정까지 묶는다.
create unique index notification_copyright_reporter_unique
    on notification (event_type, copyright_report_id)
 where copyright_report_id is not null and user_id is null;

create unique index notification_copyright_uploader_unique
    on notification (event_type, copyright_report_id, user_id)
 where copyright_report_id is not null and user_id is not null;

-- 외래키 쪽 인덱스(`ForeignKeyIndexTest`). 위 둘은 `event_type` 이 앞이라 신고로 찾는 데 못 쓴다.
create index notification_copyright_report_idx
    on notification (copyright_report_id) where copyright_report_id is not null;

-- 문안 넷. 법이 담으라는 것 — 중단 통보는 그 사실과 근거(시행령 제41조), 복제·전송자에게는 재개를 요구할 수 있다는 것,
-- 재개 예정 통보는 재개 요구 사실과 예정일(제103조③). 거래 통지라 `transactional` 이다.
insert into notification_template (code, version, subject, body, kind) values
    ('copyright_taken_down_reporter', 1,
     '[프로젝트샵] 저작권 침해 신고에 따라 게시를 중단했습니다',
     '프로젝트샵입니다.' || chr(10) ||
     '신고하신 「{{claimed_work}}」 관련 게시물을 {{decided_at}}에 중단했습니다(저작권법 제103조제2항).' || chr(10) ||
     '게시자가 재개를 요구하면 재개 예정일과 함께 다시 알려 드립니다.' || chr(10) ||
     '문의: {{recipient}}',
     'transactional'),
    ('copyright_taken_down_uploader', 1,
     '[프로젝트샵] 권리자의 요청으로 게시물이 중단되었습니다',
     '프로젝트샵입니다.' || chr(10) ||
     '「{{claimed_work}}」에 대한 권리주장자의 요청으로 올리신 사진의 게시를 {{decided_at}}에 중단했습니다(저작권법 제103조제2항).' || chr(10) ||
     '정당한 권리가 있으시면 통보받은 날부터 30일 안에 수령인에게 소명 자료와 함께 재개를 요구할 수 있습니다(저작권법 제103조제3항, 시행령 제42조).' || chr(10) ||
     '수령인: {{recipient}}',
     'transactional'),
    ('copyright_reinstatement_reporter', 1,
     '[프로젝트샵] 게시 재개 요구가 접수되었습니다',
     '프로젝트샵입니다.' || chr(10) ||
     '신고하신 「{{claimed_work}}」 관련 게시물에 게시자의 재개 요구가 접수되어 {{scheduled_for}}에 다시 게시할 예정입니다(저작권법 제103조제3항).' || chr(10) ||
     '그 전에 소를 제기하신 사실을 알려 주시면 재개하지 않습니다.',
     'transactional'),
    ('copyright_reinstated_uploader', 1,
     '[프로젝트샵] 게시물을 다시 게시했습니다',
     '프로젝트샵입니다.' || chr(10) ||
     '재개 요구에 따라 「{{claimed_work}}」 관련 사진을 {{reinstated_at}}에 다시 게시했습니다(저작권법 제103조제3항).',
     'transactional');
