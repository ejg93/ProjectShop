-- 동의 이력. 무엇에 언제 동의했고 언제 철회했나를 남긴다.
--
-- 계정 테이블의 불린 컬럼으로는 R7 을 못 만족한다. 불린은 "지금 동의 상태" 만 들고 있어서
-- 동의 시점을 못 대고, 철회하면 동의했던 사실 자체가 사라진다. 입증이 안 된다.
--
-- 그래서 상태가 아니라 사건을 적는다. 동의도 한 행, 철회도 한 행이다.
-- 현재 상태는 마지막 행을 읽어서 만든다(아래 current_consent 뷰).

-- 동의받을 항목. 코드가 아니라 데이터로 둔다. 약관 항목이 늘 때 배포하지 않는다.
create table consent_item (
    consent_item_id bigint generated always as identity primary key,

    -- 코드에서 참조하는 안정된 키. 개정돼도 안 바뀐다.
    code          text        not null,

    -- 약관 개정판. 개정하면 새 행이 생기고 옛 행은 남는다.
    -- 옛 행이 남아야 "이 사람은 3판에 동의했다" 가 성립한다. 덮어쓰면 무엇에 동의했는지 사라진다.
    version       int         not null default 1,

    title         text        not null,

    -- 동의받을 때 알려야 하는 것들. 알리지 않고 받은 동의는 동의가 아니다.
    --
    -- 개인정보 동의는 개인정보법 제15조제2항이 넷을 요구하고(목적·항목·기간·거부권과 불이익),
    -- 약관은 약관규제법 제3조제2항·제3항이 내용을 밝히고 설명하라고 한다.
    -- 요구가 다르므로 담는 자리도 가른다 — 정형 넷은 화면이 표로 그리고, 본문은 글로 그린다.
    --
    -- 개정판을 남기는 설계(아래 code+version)가 이것 없이는 쓰이지 못한다.
    -- "이 사람은 3판에 동의했다" 가 성립하려면 3판의 내용이 있어야 한다.
    purpose              text,
    collected_items      text,
    retention_period     text,
    refusal_disadvantage text,

    -- 약관 본문. 마크다운이다.
    -- 제3조제2항이 고객 요구 시 사본을 내주라고 해서, 보여줄 원문이 시스템에 있어야 한다.
    body                 text,

    -- 필수 동의는 거부하면 가입이 안 된다. 선택 동의는 거부해도 서비스를 준다(R7).
    is_required   boolean     not null default false,

    -- 이 항목에 동의하려면 먼저 동의해야 하는 항목. 야간 수신이 마케팅 수신에 걸린다(R14).
    -- 앱이 이 열을 보고 막는다. 종속을 코드에 적으면 항목을 데이터로 둔 의미가 없어진다.
    depends_on_id bigint      references consent_item (consent_item_id) on delete restrict,

    -- 이 판이 효력을 갖는 시각. 개정판을 미리 넣어 두고 시점에 갈아 끼운다.
    effective_at  timestamptz not null default now(),

    created_at    timestamptz not null default now(),

    constraint consent_item_code_version_key unique (code, version),

    -- 정형 넷은 전부 있거나 전부 없다. 셋만 채우면 법이 요구한 하나가 빠진 채로 동의를 받게 된다.
    constraint consent_item_notice_check check (
        (purpose is null and collected_items is null
         and retention_period is null and refusal_disadvantage is null)
        or (purpose is not null and collected_items is not null
            and retention_period is not null and refusal_disadvantage is not null)
    ),

    -- 둘 다 비면 고지할 내용이 없는 항목이 된다. 그런 항목에 받은 동의는 무엇에 대한 것인지 모른다.
    constraint consent_item_content_check
        check (body is not null or purpose is not null)
);

comment on table consent_item is
    '동의받을 항목. (code, version) 이 한 행. 개정하면 새 행이 생기고 옛 행은 안 지운다';

comment on column consent_item.depends_on_id is
    '먼저 동의해야 하는 항목. 야간 수신(R14)이 마케팅 수신에 걸린다';

-- "이 코드의 현재 판" 을 고르는 조회가 흔하다.
create index consent_item_code_idx on consent_item (code, effective_at desc);

-- 동의·철회 사건. append-only 다. update 하지 않는다.
--
-- update 로 갈면 이력이 그 자리에서 사라진다. 철회를 update 로 적는 설계는
-- 철회 시점은 남지만 동의 시점을 잃는다. 둘 다 필요하다(R7).
create table user_consent (
    user_consent_id bigint generated always as identity primary key,

    -- 계정을 물리 삭제하면 같이 지운다. audit_log 와 반대다.
    -- 감사 로그는 "누가 무엇을 했나" 라 계정이 없어져도 남아야 하지만,
    -- 동의 이력은 그 사람의 개인정보 그 자체고 계약이 끝나면 입증할 상대가 없다(R9).
    --
    -- 탈퇴로는 안 지워진다. 탈퇴는 deleted_at 을 채우는 update 고 cascade 는 delete 에만 걸린다.
    -- 지우는 것은 파기 배치(10a)다. 그때까지 acted_ip 가 남아 있다.
    user_id    bigint      not null references app_user (user_id) on delete cascade,

    -- 어느 판에 동의했나. 판까지 가리켜야 개정 후 재동의가 필요한지 판단할 수 있다.
    consent_item_id    bigint      not null references consent_item (consent_item_id) on delete restrict,

    -- true = 동의, false = 철회. 거부도 false 로 적는다.
    -- 선택 항목을 안 건드린 것과 거부한 것이 갈린다 — 안 건드리면 행이 아예 없다.
    granted    boolean     not null,

    -- 어디서 한 행동인가. 'signup', 'mypage', 'withdraw'.
    -- 동의를 받은 화면이 무엇이었나가 분쟁에서 쟁점이 된다.
    source     text        not null,

    -- 동의 입증에 쓴다. 개인정보라 파기 대상이고, 그래서 위 cascade 에 같이 걸린다.
    acted_ip   inet,

    acted_at   timestamptz not null default now()
);

comment on table user_consent is
    '동의·철회 사건. append-only. 현재 상태는 current_consent 뷰가 만든다';

comment on column user_consent.granted is
    'true=동의, false=철회·거부. 안 건드린 항목은 행이 없다';

-- "이 사람의 이 항목 최근 행" 이 현재 상태 조회의 전부다.
create index user_consent_current_idx on user_consent (user_id, consent_item_id, acted_at desc);

-- 현재 동의 상태. 항목 코드별로 마지막 사건 하나를 고른다.
--
-- 뷰로 두는 이유는 이 distinct on 을 앱 여기저기가 베껴 쓰기 시작하면
-- 정렬 키를 하나 빠뜨린 곳이 조용히 옛 값을 읽기 때문이다.
--
-- 정렬에 id 를 같이 넣는다. 같은 트랜잭션에서 동의와 철회가 연달아 들어가면
-- now() 가 같은 값이라 acted_at 만으로는 순서가 안 정해진다.
create view current_consent as
select distinct on (uc.user_id, ci.code)
       uc.user_id,
       ci.code    as item_code,
       ci.version as item_version,
       uc.consent_item_id,
       uc.granted,
       uc.acted_at
  from user_consent uc
  join consent_item ci on ci.consent_item_id = uc.consent_item_id
 order by uc.user_id, ci.code, uc.acted_at desc, uc.user_consent_id desc;

comment on view current_consent is
    '항목 코드별 마지막 사건. 코드로 묶어서 개정 전 판에 한 동의도 여기 잡힌다';

-- 항목 시드.
--
-- 필수 둘은 계약과 개인정보 수집이다. 이 둘을 거부하면 서비스가 성립하지 않는다.
-- 마케팅 셋은 전부 선택이고, 거부해도 서비스를 그대로 준다(R7).
--
-- 마케팅을 채널별로 쪼갠 이유는 메일은 받고 문자는 안 받겠다는 선택이 실제로 흔해서다.
-- 하나로 두면 나중에 못 쪼갠다 — 기존 동의가 어느 채널에 대한 것이었는지 알 방법이 없다.
-- 이용약관은 본문을 담는다. 정형 넷은 개인정보 고지용이라 여기 해당이 없다.
--
-- 본문은 이 프로젝트가 실제로 하는 것만 적는다. 실제 서비스 문안을 베껴 오면
-- 스키마에 없는 것을 약속하게 되고, 그건 지킬 수 없는 약관이다.
insert into consent_item (code, title, is_required, body) values
    ('terms_of_service', '이용약관', true, $$
## 제1조 (목적)

이 약관은 회사가 운영하는 통신판매중개 서비스의 이용 조건을 정합니다.

## 제2조 (중개자의 지위)

**회사는 통신판매중개자이며 통신판매의 당사자가 아닙니다.**
상품의 판매 주체는 각 판매자이고, 상품 정보와 거래에 관한 책임은 판매자에게 있습니다.

## 제3조 (청약철회)

**상품을 받은 날부터 7일 이내에 청약철회를 할 수 있습니다.**
다만 법이 정한 사유에 해당하면 철회가 제한되며, 제한되는 상품은 주문 전에 표시합니다.

## 제4조 (회원 탈퇴)

회원은 언제든지 탈퇴할 수 있습니다. 탈퇴 후 30일이 지나면 개인정보를 파기하며,
법령이 보존을 요구하는 거래기록은 해당 기간 동안 분리하여 보관합니다.
$$);

-- 개인정보 항목은 정형 넷을 채운다(개인정보법 제15조제2항).
--
-- 수집 항목에 접속 IP 가 들어 있다. user_consent.acted_ip 가 실제로 받고 있는 값이고,
-- 스키마에 있는데 고지에 없으면 근거 없이 받는 것이 된다.
insert into consent_item (code, title, is_required,
                          purpose, collected_items, retention_period, refusal_disadvantage) values
    ('privacy_collect', '개인정보 수집·이용', true,
     '회원 식별과 주문 처리, 고객 문의 응대',
     '이메일, 이름, 비밀번호(암호화 저장), 동의 시점의 접속 IP',
     '탈퇴 후 30일까지. 법령이 보존을 요구하는 거래기록은 해당 기간 동안 분리 보관',
     '거부할 수 있으나 회원 가입이 되지 않습니다'),

    ('marketing_email', '광고성 정보 수신 (이메일)', false,
     '신규 상품과 혜택 안내',
     '이메일, 이름',
     '동의를 철회할 때까지',
     '거부해도 서비스 이용에는 제한이 없습니다'),

    ('marketing_sms', '광고성 정보 수신 (문자)', false,
     '신규 상품과 혜택 안내',
     '휴대전화번호, 이름',
     '동의를 철회할 때까지',
     '거부해도 서비스 이용에는 제한이 없습니다');

-- 야간 수신은 21시~08시 전송에 필요한 별도 동의다(R14).
-- 채널 동의와 따로 받아야 하므로 항목이 따로 있고, 채널을 거부한 사람에게는 물어볼 이유가 없어서
-- 이메일 수신 동의에 걸어 둔다.
insert into consent_item (code, title, is_required, depends_on_id,
                          purpose, collected_items, retention_period, refusal_disadvantage)
select 'marketing_night', '야간 광고성 정보 수신 (21시~08시)', false, consent_item_id,
       '21시부터 다음날 08시 사이의 광고성 정보 전송',
       '이메일, 이름',
       '동의를 철회할 때까지',
       '거부해도 주간 수신에는 영향이 없습니다'
  from consent_item
 where code = 'marketing_email' and version = 1;
