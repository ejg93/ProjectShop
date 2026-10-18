-- 처리방침의 새 판 — 위탁 표·국외 이전 절·요청 제한의 접속 IP·후기 작성자 표시 목적(`Q271`, `D2` R43).
--
-- 5절이 「외부에 위탁하고 있지 않습니다」(`V21`)였는데 운영이 Railway(서버·Postgres·Redis)와 Cloudflare R2(사진) 위라
-- **처음부터 위탁이자 국외 이전이었다**. 개인정보 보호법 제26조제2항이 수탁자와 업무를 공개하라 하고, 제28조의8제1항제3호가
-- 계약 이행을 위한 국외 보관은 제30조 처리방침에 공개하면 동의 없이 된다고 한다 — 그래서 동의 항목은 안 늘린다.
-- 제2항이 공개할 것을 든다: 항목 · 국가·시기·방법 · 받는 자의 이름과 연락처 · 목적·보유기간 · 거부 방법·절차·효과.
--
-- **국가는 사용자가 줬다**(2026-10-19 — Railway US West, R2 APAC). 연락처는 각 사의 공개 문서에서 읽었다 — Railway 는
-- DPA 의 privacy@railway.com, Cloudflare 는 개인정보 고지의 privacyquestions@cloudflare.com. 지어내지 않는다(`R26` 선).
--
-- 8절: `RateLimitFilter` 가 손님 주소를 Redis 키로 1분 센다(키가 1분 뒤 만료). 감사 기록도 접속지를 3년 남긴다(`V129`, `Q273`). 2절: 후기 목록이 작성자 이름을 가려
-- 그린다(`Q272`) — 남에게 보이는 것이라 목적을 적는다.
--
-- **시행은 7일 뒤다** — 방침 10항의 약속(`V116` 과 같다, `D2` R11). 이미 나간 판은 안 고친다(`policy_document_immutable`).
insert into policy_document (code, version, title, body, effective_at)
select code,
       version + 1,
       title,
       replace(replace(replace(body,
               E'- 고객 문의 응대\n',
               E'- 고객 문의 응대\n'
               || E'- 상품 후기 게시 (후기에는 작성자 이름의 일부만 다른 이용자에게 보여 드립니다)\n'),
           E'현재 개인정보 처리를 외부에 위탁하고 있지 않습니다.\n'
           || E'위탁이 생기면 이 방침을 개정하여 위탁받는 자와 업무 내용을 알려 드립니다.\n',
           E'서비스를 제공하기 위해 아래와 같이 개인정보 처리 업무를 위탁합니다.\n\n'
           || E'| 수탁자 | 위탁 업무 | 보관 국가 |\n'
           || E'|---|---|---|\n'
           || E'| Railway Corporation | 서버·데이터베이스·로그인 상태 저장소 운영 | 미국 |\n'
           || E'| Cloudflare, Inc. | 상품 사진과 후기 사진 파일 저장 | 아시아·태평양 지역 |\n\n'
           || E'위탁받는 자나 업무가 바뀌면 이 방침을 개정하여 알려 드립니다.\n\n'
           || E'## 5-1. 개인정보의 국외 이전\n\n'
           || E'**위 수탁자가 국외에서 개인정보를 보관하므로 회원님의 개인정보가 국외로 이전됩니다.**\n'
           || E'서비스 이용 계약을 이행하기 위한 보관이어서 따로 동의를 받지 않고 이 방침으로 알려 드립니다(개인정보 보호법 제28조의8제1항제3호).\n\n'
           || E'| 이전받는 자 (연락처) | 이전되는 국가 | 이전되는 항목 |\n'
           || E'|---|---|---|\n'
           || E'| Railway Corporation (privacy@railway.com) | 미국 | 1절의 수집 항목 전부와 주문·문의·후기 기록 |\n'
           || E'| Cloudflare, Inc. (privacyquestions@cloudflare.com) | 아시아·태평양 지역 | 상품 사진과 후기 사진 파일 |\n\n'
           || E'- 이전 시기와 방법: 저장할 때마다 암호화된 네트워크로 보내 수탁자의 서버에 보관합니다\n'
           || E'- 이용 목적: 서비스 제공을 위한 보관\n'
           || E'- 보유 기간: 3절의 기간과 같습니다\n'
           || E'- 거부 방법과 효과: 탈퇴하시면 이전이 멈춥니다. 서비스가 이 수탁자 위에서 돌아가므로 회원 자격을 둔 채 이전만 거부하실 수는 없습니다\n'),
           E'로그인 상태를 유지하기 위한 세션 쿠키도 함께 사용합니다.\n',
           E'로그인 상태를 유지하기 위한 세션 쿠키도 함께 사용합니다.\n\n'
           || E'**짧은 시간에 요청이 몰리는 것을 막기 위해 접속 IP 를 1분간 처리합니다.** 1분이 지나면 남기지 않습니다.\n'
           || E'**보안을 위해 남기는 작업 기록에는 그 작업을 한 접속 IP 를 같이 남깁니다.** 그 기록과 함께 3년간 보관합니다.\n'),
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

    if policy not like '%상품 후기 게시%'
       or policy not like '%| Railway Corporation | 서버·데이터베이스%'
       or policy not like '%## 5-1. 개인정보의 국외 이전%'
       or policy not like '%접속 IP 를 1분간 처리합니다%'
       or policy not like '%작업 기록에는 그 작업을 한 접속 IP%'
       or policy like '%위탁하고 있지 않습니다%' then
        raise exception '처리방침 새 판에 바꾼 문안이 없다 — 앞 판의 문장이 달라졌다';
    end if;
end
$$;
