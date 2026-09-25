-- 처리방침의 새 판 — 대리인의 열람등요구 창구와 개인정보 보호책임자 연락처(`Q209`).
--
-- 개인정보 보호법 제38조제1항·시행령 제45조가 대리인(법정대리인·위임받은 자)의 열람등요구를 인정하는데 7절에 그 절차가
-- 없었고, 9절은 없는 「고객센터」를 가리켰다(`V27` 서면이 「별도의 고객센터를 운영하지 않는다」고 밝혔다). 대리인은 계정이
-- 없어서 내 문의로는 못 받는다. 제30조제1항제6호(보호책임자의 연락처)도 9절에 없었다.
--
-- **창구는 수신 이메일 하나다**(2026-09-25 사용자 결정, 주소는 2026-09-26 사용자가 줬다 — 지어내지 않는다). 대리인 창구와
-- 보호책임자 연락처가 같은 주소다. 답하는 기한은 받은 날부터 10일(제35조제3항·시행령 제41조제4항, 정정·삭제·처리정지도 같다).
--
-- **시행은 7일 뒤다** — 방침 10항이 「시행 7일 전부터 알린다」고 약속했다(`V112` 와 같다, `D2` R11). 이미 나간 판은
-- 안 고친다(`policy_document_immutable`).
insert into policy_document (code, version, title, body, effective_at)
select code,
       version + 1,
       title,
       replace(replace(body,
               E'필수 동의는 철회하실 수 없으며, 원하시는 경우 탈퇴로 처리해 드립니다.\n',
               E'필수 동의는 철회하실 수 없으며, 원하시는 경우 탈퇴로 처리해 드립니다.\n\n'
               || E'**대리인이 요구하시는 경우** — 법정대리인이나 위임을 받은 분도 위 권리를 대신 행사하실 수 있습니다.\n'
               || E'위임장과 대리인의 신분증 사본을 ejg933@gmail.com 으로 보내 주시면, 받은 날부터 10일 안에 결과를 알려 드립니다.\n'),
           '개인정보와 관련한 문의는 고객센터로 보내 주시기 바랍니다.',
           E'개인정보 보호책임자 연락처: ejg933@gmail.com\n'
           || '개인정보와 관련한 문의와 열람·정정·삭제·처리정지 요구는 이 주소로 보내 주시기 바랍니다.'),
       now() + interval '7 days'
  from policy_document
 where code = 'privacy_policy'
 order by version desc
 limit 1;

-- **바꾸기가 먹었나 본다.** `replace` 는 못 찾으면 조용히 원문을 돌려준다 — 그러면 옛 문안 그대로인 새 판이 선다.
do $$
declare
    policy text;
begin
    select body into policy from policy_document
     where code = 'privacy_policy' order by version desc limit 1;

    if policy not like '%대리인이 요구하시는 경우%'
       or policy not like '%개인정보 보호책임자 연락처: ejg933@gmail.com%'
       or policy like '%고객센터%' then
        raise exception '처리방침 새 판에 바꾼 문안이 없다 — 앞 판의 문장이 달라졌다';
    end if;
end
$$;
