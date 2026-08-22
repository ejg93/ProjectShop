-- 수신동의 정기 확인(청크 55b, 정보통신망법 제50조제8항·시행령 제62조의3).
--
-- **동의받은 날부터 2년마다 확인해야 한다.** 확인은 <b>반복되는 일</b>이라
-- 「이 동의 행에 통지가 나갔나」로는 두 번째 주기를 못 잰다 — 나갔다는 사실은 한 번만 참이 된다.
-- 그래서 <b>마지막으로 확인한 시각</b>을 동의 이력에 남기고 거기서 다음 주기를 센다.

alter table user_consent add column reconfirmed_at timestamptz;

comment on column user_consent.reconfirmed_at is
    '마지막으로 수신동의 여부를 확인한 시각. 다음 2년을 여기서 센다(시행령 제62조의3, 55b)';

-- 확인이 밀린 동의를 고르는 질의가 이 컬럼과 `acted_at` 을 같이 본다.
create index user_consent_reconfirm_idx
    on user_consent (consent_item_id, coalesce(reconfirmed_at, acted_at))
    where granted;

-- `55a` 가 건 유니크가 이 통지를 막는다. **처리 결과는 의사표시당 한 번이지만
-- 확인은 2년마다 반복**이라, 같은 동의 행에 여러 번 나가야 한다.
--
-- 그래서 유니크를 처리 결과에만 건다. 확인의 중복은 `reconfirmed_at` 이 막는다 —
-- 성격이 달라서 막는 수단도 다르다.
drop index notification_user_consent_event_unique;

create unique index notification_consent_result_unique
    on notification (event_type, user_consent_id)
 where user_consent_id is not null and event_type = 'consent_result';

-- 사건 목록을 연다.
alter table notification drop constraint notification_event_type_check;

alter table notification add constraint notification_event_type_check
    check (event_type in ('order_placed', 'payment_completed', 'supply_delayed',
                          'refund_completed', 'password_reset', 'email_change',
                          'advertisement', 'consent_result', 'consent_reconfirm'));

-- 문안. 시행령 제62조의3 이 담을 것을 셋으로 정했다 —
-- 전송자의 명칭, 수신동의 사실과 동의한 날짜, 유지·철회 의사표시 방법이다.
--
-- **광고가 아니다.** 확인 통지에 혜택 안내를 얹으면 그것이 광고 전송이 되고,
-- 야간에 나가면 별도 동의까지 필요해진다. 종류가 `transactional` 인 이유다.
insert into notification_template (code, version, subject, body, kind) values
    ('consent_reconfirm', 1,
     '[프로젝트샵] 광고성 정보 수신동의를 확인해 주세요',
     '프로젝트샵입니다.' || chr(10) ||
     '{{item_title}} 항목에 {{acted_at}}에 동의하셨습니다.' || chr(10) ||
     '계속 받으실 경우 따로 하실 일이 없습니다. 원하지 않으시면 마이페이지 > 동의 내역에서 ' ||
     '언제든지 철회하실 수 있습니다.',
     'transactional');
