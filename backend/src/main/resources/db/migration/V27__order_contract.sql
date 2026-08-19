-- 계약내용 서면. 전자상거래법 제13조제2항 후단(D2 R22).
--
-- 「계약이 체결되면 계약자에게 각 호의 사항이 기재된 계약내용에 관한 서면을
--  재화등을 공급할 때까지 교부하여야 한다」
--
-- 앞단의 표시·광고 의무와 별개 행위다. 앞은 사기 전에 보여주는 것이고 뒤는 사고 나서 주는 것이다.
--
-- 중개자 고지로 안 빠져나간다. 제20조의2제3항이 못 면하는 범위에 제13조를 넣는다(D2 R5).
--
--
-- 화면 바닥 링크로는 안 된다. 링크는 「지금의 문안」을 가리켜서, 청약철회 안내를 개정하면
-- 지나간 주문의 계약 조건까지 바뀐 것처럼 보인다. 주문이 그때의 판을 가리켜야 한다.


-- 1. 시행된 판은 못 고친다.
--
-- 이것이 먼저다. 판을 가리키는 설계는 그 판이 안 바뀔 때만 성립한다.
--
-- 전자문서법 제4조의2 가 전자문서를 서면으로 보는 요건을 둘로 정한다.
--   1호  전자문서의 내용을 열람할 수 있을 것
--   2호  작성·변환되거나 송신·수신 또는 저장된 때의 형태 또는 그와 같이
--        재현될 수 있는 형태로 보존되어 있을 것
--
-- 2호가 이 트리거를 요구한다. 본문을 고칠 수 있으면 「그때의 형태」가 남지 않고,
-- 그러면 우리가 교부했다고 주장하는 서면이 서면이 아니다.
--
-- V21·V11 이 「새 판이 행으로 쌓이고 옛 행은 안 지운다」고 적어 뒀는데 그것은 주석이라
-- 아무것도 안 막았다(D23 축 2 의 5위). 여기서 2위로 내린다.
--
-- 시행 전인 판은 연다. 아직 아무도 그것에 계약하지 않았고 아무도 그것을 고지받지 않았다 —
-- 오타를 고치려면 판을 새로 쌓는 수밖에 없게 만들면 시행도 안 된 판이 이력에 쌓인다.
-- 판단은 언제나 OLD 를 본다. 시행된 판인지가 기준이고, 그 사실은 고치기 전 값에 있다 —
-- NEW 를 보면 effective_at 을 미래로 밀면서 본문을 같이 고치는 것이 통과한다.
create or replace function reject_effective_document_change() returns trigger
language plpgsql as $$
begin
    if old.effective_at <= now() then
        raise exception '시행된 %는 고칠 수 없다. 개정은 새 판을 쌓는 것이다(전자문서법 제4조의2)',
            tg_table_name;
    end if;
    return case tg_op when 'DELETE' then old else new end;
end;
$$;

create trigger policy_document_immutable
    before update or delete on policy_document
    for each row execute function reject_effective_document_change();

create trigger consent_item_immutable
    before update or delete on consent_item
    for each row execute function reject_effective_document_change();


-- 2. 분쟁 처리 안내. 제13조제2항 8호.
--
-- 「소비자피해보상의 처리, 재화등에 대한 불만 처리 및 소비자와 사업자 사이의
--  분쟁 처리에 관한 사항」
--
-- 담은 문서가 없어서 여기서 세운다. 청약철회 안내에 절을 더하지 않은 이유는 개정 주기가
-- 달라서다 — 한 판으로 묶으면 한쪽만 고쳐도 다른 쪽까지 새 판이 되고, 주문이 가리키는 판이 바뀐다.
--
-- 지어낼 필요가 없는 것만 적는다(13a-2 가 그은 선). 자체 고객센터·처리 기한 같은 것은
-- 실제로 없으므로 안 적고, 없다는 사실을 본문에 밝힌다.
--
-- 적은 것의 출처는 전부 법이다.
--   제20조의2         중개자와 판매자의 책임 범위
--   제33조·소비자기본법 소비자분쟁조정위원회
--   소비자기본법 제55조 한국소비자원 피해구제
--   제* 공정거래위원회 신고
insert into policy_document (code, title, body) values
    ('dispute_resolution', '분쟁 처리 안내', $$
## 1. 누가 책임을 지나

이 서비스는 **통신판매중개자**이며 각 상품의 통신판매 당사자는 판매자입니다.
상품의 하자, 배송, 청약철회 등 거래에 관한 책임은 판매자에게 있습니다.

**다만 이 서비스가 대금을 직접 받으므로, 대금 환급에 관한 의무는 판매자와 함께 집니다.**
(전자상거래법 제18조제11항, 제20조의2제3항)

판매자의 상호·대표자·주소·연락처는 상품 상세 화면과 주문서에서 확인하실 수 있습니다.

## 2. 불만을 접수하시는 방법

**현재 이 서비스는 시연용이며 별도의 고객센터를 운영하지 않습니다.**
실제 운영 중인 고객센터가 없는데 있는 것처럼 안내드리지 않기 위하여 밝혀 둡니다.

주문에 관한 처리 내역은 **주문 상세 화면**에서 언제든지 확인하실 수 있습니다.
(전자상거래법 제6조제3항)

## 3. 외부 분쟁조정기구

사업자와 합의가 이루어지지 않으면 아래 기관에 조정을 신청하실 수 있습니다.

| 기관 | 무엇을 하나 |
|---|---|
| **한국소비자원 소비자상담센터** | 소비자 피해구제 신청 (소비자기본법 제55조) |
| **소비자분쟁조정위원회** | 사업자와의 분쟁 조정 (소비자기본법 제60조) |
| **공정거래위원회** | 전자상거래법 위반 행위 신고 |
| **전자거래분쟁조정위원회** | 전자거래에서 발생한 분쟁의 조정 |

## 4. 조정의 효력

조정안을 양쪽이 받아들이면 **재판상 화해와 같은 효력**이 생깁니다.
(소비자기본법 제67조제4항)

조정을 신청하셔도 소송을 제기하실 권리는 그대로 남습니다.
$$);


-- 3. 주문이 가리키는 계약 문서.
--
-- 주문 하나에 여러 문서가 붙는다. 문서가 두 표에 걸쳐 있어서 한 표로 받는다 —
-- shop_order 에 컬럼으로 두면 서로 다른 표를 가리키는 외래키가 나란히 붙고,
-- 문서가 하나 늘 때마다 5년 보존하는 표에 마이그레이션이 들어간다.
--
-- V18 이 주문 층과 셀러 묶음 층을 한 표에 받은 것과 같은 모양이고, 같은 방식으로
-- 「정확히 하나를 가리킨다」를 check 로 막는다.
create table order_contract_document (
    order_contract_document_id bigint not null generated always as identity primary key,

    -- 주문을 지울 때 같이 사라진다. 파기 배치가 지우는 자리에서만 쓰인다(D23).
    order_id bigint not null references shop_order (order_id) on delete cascade,

    -- 둘 중 정확히 하나를 채운다.
    --
    -- restrict 다. 문서를 못 지우는 것이 아니라, 지우려면 그것을 가리키는 주문이
    -- 없어야 한다는 뜻이다 — 계약 조건을 잃은 주문이 남지 않는다.
    policy_document_id bigint references policy_document (policy_document_id) on delete restrict,
    consent_item_id    bigint references consent_item (consent_item_id)       on delete restrict,

    -- 제13조제2항의 몇 호를 채우는 문서인가. 화면이 무엇을 어느 자리에 그릴지 정한다.
    --
    -- 문서 코드로 대신하지 않는다. 코드는 우리가 붙인 이름이고 이것은 법이 정한 목록이라,
    -- 문서를 쪼개거나 합쳐도 이 값은 안 바뀐다.
    clause text not null,

    created_at timestamptz not null default now(),

    constraint order_contract_document_target_check
        check ((policy_document_id is not null) <> (consent_item_id is not null)),

    -- 같은 주문에 같은 호가 두 번 붙으면 화면이 어느 것을 그릴지 못 고른다.
    constraint order_contract_document_clause_unique unique (order_id, clause),

    -- 법이 정한 목록이라 닫는다(D23 「법이 인정한 목록은 닫는다」).
    --   withdrawal  5호 청약철회의 기한·행사방법·효과
    --   exchange    6호 교환·반품·보증과 대금 환불, 환불 지연 배상금
    --   dispute     8호 소비자피해보상의 처리, 불만 처리, 분쟁 처리
    --   terms       9호 거래에 관한 약관
    constraint order_contract_document_clause_check
        check (clause in ('withdrawal', 'exchange', 'dispute', 'terms'))
);

comment on table order_contract_document is
    '주문 시점의 계약 문서 판. 제13조제2항 후단의 서면이 가리키는 것이다(D2 R22)';
comment on column order_contract_document.clause is
    '제13조제2항의 호. 문서를 쪼개거나 합쳐도 이 값은 안 바뀐다';

create index order_contract_document_order_idx on order_contract_document (order_id);
create index order_contract_document_policy_idx on order_contract_document (policy_document_id);
create index order_contract_document_consent_idx on order_contract_document (consent_item_id);
