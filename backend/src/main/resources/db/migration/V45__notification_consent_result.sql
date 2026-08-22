-- 동의 처리 결과 통지(청크 55a, 정보통신망법 제50조제7항·시행령 제62조의2).
--
-- **의사표시 하나에 통지 하나다.** 시행령 제62조의2 가 「의사를 표시한 날부터 14일 이내」로 못박아서
-- 어느 의사표시에 대한 통지인지가 붙어야 기한을 잴 수 있다.
--
-- 그래서 대상 칸이 하나 는다. 지금까지는 주문·셀러주문·환불 셋이었는데
-- 이 통지가 가리키는 것은 **동의 이력의 그 행**이다.

alter table notification
    add column user_consent_id bigint references user_consent (user_consent_id) on delete cascade;

comment on column notification.user_consent_id is
    '어느 의사표시에 대한 처리 결과 통지인가. 시행령 제62조의2 의 14일을 여기서 잰다(55a)';

-- 대상 칸이 넷이 됐다. 「많아야 하나」는 그대로다 —
-- 광고와 비밀번호 재설정처럼 걸리는 자원이 없는 통지가 있어서 `= 1` 이 아니다.
alter table notification drop constraint notification_target_check;

alter table notification add constraint notification_target_check
    check (num_nonnulls(order_id, seller_order_id, refund_id, user_consent_id) <= 1);

-- 같은 의사표시에 두 번 안 보낸다. 대상마다 따로 거는 것은 `V43` 과 같은 이유다.
create unique index notification_user_consent_event_unique
    on notification (event_type, user_consent_id) where user_consent_id is not null;

-- 사건 목록을 연다. **목록이 닫혀 있어서 마이그레이션 없이는 못 남긴다** — 그것이 강제 지점이다.
alter table notification drop constraint notification_event_type_check;

alter table notification add constraint notification_event_type_check
    check (event_type in ('order_placed', 'payment_completed', 'supply_delayed',
                          'refund_completed', 'password_reset', 'email_change',
                          'advertisement', 'consent_result'));

-- 문안. 시행령 제62조의2 가 담을 것을 셋으로 정했다 —
-- 전송자의 명칭, 의사표시 사실과 날짜, 처리 결과다.
--
-- **광고성 정보를 넣으면 안 된다.** 이 통지에 혜택 안내를 얹는 순간 그것이 광고 전송이 되고,
-- 철회한 사람에게 보낸 것이면 제50조제2항 위반이다.
insert into notification_template (code, version, subject, body, kind) values
    ('consent_result', 1,
     '[프로젝트샵] 광고성 정보 수신 설정이 처리되었습니다',
     '프로젝트샵입니다.' || chr(10) ||
     '{{item_title}} 항목에 대한 {{action}} 의사를 {{acted_at}}에 접수했습니다.' || chr(10) ||
     '처리 결과: {{result}}' || chr(10) ||
     '설정은 마이페이지에서 언제든지 다시 바꾸실 수 있습니다.',
     'transactional');
