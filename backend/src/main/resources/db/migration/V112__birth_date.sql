-- 성인만 가입한다(`11b`, 2026-09-23 사용자 결정 (나)).
--
-- **왜 성인만인가.** 개인정보 보호법 제22조의2 는 만 14세 미만 아동의 개인정보를 처리하려면 법정대리인의 동의를
-- 받고 그 동의를 **확인**하라고 하는데, 확인할 수단(휴대폰 본인확인)이 데모에 없다. 민법 제5조는 미성년자(제4조 —
-- 만 19세 미만)의 법률행위를 법정대리인이 취소할 수 있게 한다. 만 19세 미만을 받지 않으면 아동 개인정보를 처리하지
-- 않고 미성년자의 취소도 안 생긴다(`D2` R13·R29).
--
-- **나이는 민법 제158조로 센다** — 출생일을 산입해 만 나이로 센다. 생일 당일 0시에 한 살이 는다.
-- 윤일(2월 29일)생은 평년에 3월 1일에 는다(제160조제3항 — 해당일이 없으면 그 달 말일로 기간이 끝난다).
-- PostgreSQL 의 `age()` 와 Java 의 `Period.between` 이 둘 다 이렇게 센다. `+ interval '19 years'` 는 2월 28일로
-- 당겨서 하루 이르다 — 그래서 안 쓴다.
--
-- **「오늘」은 KST 다**(`time-rules.md` 「판단은 KST」). 세션 시간대가 UTC 라 `current_date` 를 그대로 쓰면
-- 0시~9시(KST) 사이에 하루 늦게 센다.
--
-- **기존 계정은 비어 있다.** 이 칸이 생기기 전에 가입한 계정과 시드(`V900`~)에는 생년월일이 없다 — 그래서 not null 을
-- 못 건다. 가입 입구가 `@NotNull` 로 받고 이 트리거가 값이 있을 때 나이를 본다.

alter table app_user add column birth_date date;

comment on column app_user.birth_date is
    '생년월일. 가입 때 받아 만 19세 이상인지 본다(11b). 이 칸이 생기기 전 계정·시드는 비어 있다. 파기 때 같이 지운다';

create function app_user_adult_only() returns trigger
language plpgsql as $$
begin
    if new.birth_date is not null
       and extract(year from age((now() at time zone 'Asia/Seoul')::date, new.birth_date)) < 19 then
        raise exception using
            errcode    = 'check_violation',
            constraint = 'app_user_adult_only',
            message    = '만 19세 미만은 가입할 수 없다 (민법 제4조, 11b)';
    end if;
    return new;
end;
$$;

create trigger app_user_adult_only
    before insert or update of birth_date on app_user
    for each row execute function app_user_adult_only();


-- 수집·이용 고지의 새 판(개인정보 보호법 제15조제2항). **오늘부터 시행한다.**
--
-- 생년월일을 받는 순간이 곧 이 고지에 동의받는 순간이라(가입), 고지가 수집보다 늦으면 알리지 않고 받는 것이 된다.
-- 기존 회원에게서는 생년월일을 안 받는다 — 판이 바뀌어도 이미 동의한 사람이 새로 지는 것이 없다.
--
-- 지금 판을 읽어서 두 칸만 바꾼다(`V34` 와 같은 수). 통째로 베끼면 그 사이 고친 문안이 조용히 되돌아간다.
insert into consent_item (code, version, title, is_required, sort_no,
                          purpose, collected_items, retention_period, refusal_disadvantage, effective_at)
select code, version + 1, title, is_required, sort_no,
       purpose || ', 만 19세 이상 확인',
       replace(collected_items, '이메일, 이름, ', '이메일, 이름, 생년월일, '),
       retention_period, refusal_disadvantage, now()
  from consent_item
 where code = 'privacy_collect'
 order by effective_at desc, version desc
 limit 1;


-- 처리방침의 새 판(개인정보 보호법 제30조). **시행은 7일 뒤다** — 방침 10항이 「시행 7일 전부터 알린다」고 약속했고
-- `D2` R11 이 그 설계를 든다(시행령 제31조제3항).
--
-- 수집은 오늘 시작하는데 방침은 7일 뒤라 그 사이 방침이 생년월일을 안 적는다. 그래도 알리지 않고 받는 것은 아니다 —
-- 수집의 근거는 가입 때의 동의(제15조제1항제1호)고, 위 고지가 생년월일을 적은 채 그 자리에서 먼저 나간다.
-- 방침을 오늘부터 시행하면 10항의 약속을 어기고, 수집을 7일 미루면 그 사이 미성년자가 가입한다.
--
-- **대리인의 열람등요구(제38조) 절은 여기서 안 쓴다.** 접수 창구가 정해지지 않았다 — 행은 「이메일 접수」라 적었는데
-- 운영 이메일이 없다(`V27` 이 「별도의 고객센터를 운영하지 않는다」고 밝혔다). `Q209` 가 창구를 정하고 새 판을 낸다.
insert into policy_document (code, version, title, body, effective_at)
select code,
       version + 1,
       title,
       replace(replace(replace(body,
               '| 필수 | 이메일, 이름, 비밀번호 | 회원 가입 |',
               '| 필수 | 이메일, 이름, 비밀번호, 생년월일 | 회원 가입 |'),
           E'- 회원 식별과 로그인\n',
           E'- 회원 식별과 로그인\n- 만 19세 이상인지 확인 (**만 19세 미만은 가입하실 수 없으며, 그래서 만 14세 미만 아동의 개인정보를 처리하지 않습니다**)\n'),
           '탈퇴하시면 5일이 지난 뒤 이메일, 이름, 비밀번호를 지웁니다.',
           '탈퇴하시면 5일이 지난 뒤 이메일, 이름, 비밀번호, 생년월일을 지웁니다.'),
       now() + interval '7 days'
  from policy_document
 where code = 'privacy_policy'
 order by effective_at desc, version desc
 limit 1;

-- **바꾸기가 먹었나 본다.** `replace` 는 못 찾으면 조용히 원문을 돌려준다 — 그러면 옛 문안 그대로인 새 판이 선다.
do $$
declare
    policy  text;
    collect text;
begin
    select body into policy from policy_document
     where code = 'privacy_policy' order by version desc limit 1;
    select collected_items into collect from consent_item
     where code = 'privacy_collect' order by version desc limit 1;

    if policy not like '%비밀번호, 생년월일 | 회원 가입 |%'
       or policy not like '%만 19세 미만은 가입하실 수 없으며%'
       or policy not like '%비밀번호, 생년월일을 지웁니다.%' then
        raise exception '처리방침 새 판에 바꾼 문안이 없다 — 앞 판의 문장이 달라졌다';
    end if;
    if collect not like '%생년월일%' then
        raise exception '수집·이용 고지 새 판에 생년월일이 없다 — 앞 판의 항목 문장이 달라졌다';
    end if;
end
$$;
