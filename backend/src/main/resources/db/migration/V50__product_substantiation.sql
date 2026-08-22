-- 표시·광고의 실증자료(청크 13f-1, `D2` R32, 표시광고법 제5조).
--
-- 「사업자등은 자기가 한 표시·광고 중 **사실과 관련한 사항**에 대하여는 실증할 수 있어야 한다」고 하고,
-- 공정위가 요청하면 **15일 이내**에 내야 한다. 지금은 검수가 반려만 하고 <b>근거를 받는 자리가 없었다.</b>
--
-- **문구는 셀러가 쓰지만 요청은 우리에게도 온다** — 전자상거래법 제20조의2 가 중개자 책임을 두었다.
--
-- **주장 하나에 근거 하나다.** 「국내 1위」·「정품」·「3년 보증」이 한 칸에 섞이면
-- 제출 요구가 왔을 때 <b>어느 부분이 답인지 사람이 다시 가른다.</b>

create table product_substantiation (
    product_substantiation_id bigint not null generated always as identity primary key,

    product_id bigint not null references product (product_id) on delete cascade,

    -- 어느 주장의 근거인가. 상품 설명에 쓴 문구를 그대로 옮긴다.
    claim text not null,

    -- 근거 글. **파일이 아니다** — 올리는 자리를 `D17`(파일·미디어 규약)이 아직 안 정했다.
    -- 정해지면 이 표에 첨부를 붙인다.
    evidence text not null,

    -- 시험성적서·인증 페이지 주소 같은 것. 없을 수 있다.
    source_url text,

    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),

    constraint product_substantiation_claim_length_check
        check (length(claim) between 1 and 200),

    constraint product_substantiation_evidence_length_check
        check (length(evidence) between 1 and 2000),

    -- 같은 주장을 두 번 적으면 어느 것이 최신인지 안 갈린다.
    constraint product_substantiation_claim_unique unique (product_id, claim)
);

comment on table product_substantiation is
    '표시·광고 실증자료. 사실과 관련한 문구는 실증할 수 있어야 한다(표시광고법 제5조, D2 R32)';

create index product_substantiation_product_idx
    on product_substantiation (product_id);
