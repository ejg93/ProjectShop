-- 문의 스키마(청크 58). 법 요건 셋이 여기서 닫힌다.
--
--   R28  처리정지 요구(개인정보법 제37조)와 열람등요구 이의제기(제38조제5항)
--   R25  중개자의 분쟁 해결 조치(전자상거래법 제20조제3항)
--   R34  광고성 게시물 차단(정보통신망법 제50조의7)
--
-- **앞의 둘은 받을 자리가 있어야 성립한다.** 지금은 방침 제9절의 창구를 가리키는 문구뿐이라
-- 「어디로 받나」에 코드가 답을 못 한다 — 문구는 자원이 아니다.
--
-- 셋째는 반대 방향이다. **우리는 광고를 보내는 쪽이 아니라 게시판 운영자 쪽**이고,
-- 제50조의7 은 운영자가 거부하면 게시가 중단돼야 한다고 정한다. Q&A 가 열리는 순간
-- 그 자리가 광고판이 되는데 지금은 내릴 수단이 없다.


-- 문의 하나. 상품 Q&A 와 계정에 붙는 요구가 같은 표에 있다.
--
-- **표를 안 가른다.** 셋 다 「받아서 답한다」는 같은 수명을 살고 같은 상태를 지난다 —
-- 가르면 접수·답변·차단이 표마다 사본이 되고, 「어디로 받나」의 답이 다시 여럿이 된다.
-- 갈리는 것은 **무엇에 붙느냐**뿐이라 그것만 아래 check 가 가른다.
create table inquiry (
    inquiry_id bigint not null generated always as identity primary key,

    -- 노출 번호(D9). 셀러 답변·처리정지 통지·분쟁 조정이 이 번호로 같은 건을 가리킨다.
    inquiry_number text not null,

    -- 무엇을 묻나. **이 값이 무엇에 붙는지를 정한다.**
    --
    --   product            상품 Q&A. 구매 전 문의라 리뷰와 권한 조건이 다르다
    --   processing_stop    처리정지 요구(개인정보법 제37조)
    --   access_objection   열람등요구 이의제기(제38조제5항)
    --   dispute            불만·분쟁 접수(전자상거래법 제20조제3항)
    --
    -- **뒤의 셋은 상품이 아니라 계정에 붙는다.** 처리정지는 「내 개인정보를 그만 쓰라」는
    -- 요구라 대상이 사람이고, 상품 번호를 요구하면 상품을 안 산 사람은 낼 수가 없다.
    kind text not null,

    -- 상품 Q&A 의 대상. 그 밖에는 비어 있다.
    product_id bigint references product (product_id) on delete restrict,

    -- 낸 사람. 계정에 붙는 요구는 이 값이 곧 대상이다.
    user_id bigint not null references app_user (user_id) on delete restrict,

    status text not null default 'received',

    -- 사람이 쓴 글. **3년을 산다**(아래 comment) — 파기는 거래기록 파기 배치가 한다.
    question text not null,
    answer text,

    answered_at timestamptz,

    -- 게시를 중단한 시각과 사유(정보통신망법 제50조의7).
    --
    -- **사유를 열거로 닫는다.** 자유 텍스트로 두면 법이 인정하지 않는 사유가 화면에 나가고,
    -- 「무슨 사유로 몇 건 내렸나」에 값이 답을 못 한다.
    blocked_at timestamptz,
    blocked_reason text,

    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),

    constraint inquiry_number_unique unique (inquiry_number),

    -- Q-20260823-K3M9P7. 난수 집합은 주문번호와 같다 — 0·O·1·I 를 뺀 32자(D9).
    constraint inquiry_number_format_check
        check (inquiry_number ~ '^Q-[0-9]{8}-[2-9A-HJ-NP-Z]{6}$'),

    constraint inquiry_kind_check
        check (kind in ('product', 'processing_stop', 'access_objection', 'dispute')),

    constraint inquiry_status_check
        check (status in ('received', 'answered', 'blocked', 'withdrawn')),

    -- 종류가 대상을 정한다. 상품 문의에만 상품이 있다.
    --
    -- 한쪽만 걸면 「처리정지 요구인데 상품이 붙어 있는」 행이 생기고, 그 행은
    -- 상품 화면의 목록에 섞여 나간다.
    constraint inquiry_product_check
        check ((kind = 'product') = (product_id is not null)),

    -- 답변한 문의에는 답과 시각이 있고, 안 한 문의에는 없다.
    -- 셋을 따로 두면 「답변인데 답이 없는」 행이 생긴다(refund_decision_check 와 같은 모양).
    --
    -- 아래 inquiry_block_check 와 같은 이유로 사슬로 묶는다 — 등식 하나로 쓰면
    -- **답만 써 두고 상태를 안 옮긴 행**이 통과하고, 그 답은 아무 화면에도 안 나간다.
    constraint inquiry_answer_check
        check ((status = 'answered') = (answered_at is not null)
               and (answered_at is not null) = (answer is not null)),

    -- 내린 게시물에는 시각과 사유가 있고, 그 밖에는 없다.
    --
    -- **셋을 사슬로 묶는다.** 「상태 = (시각과 사유가 둘 다 있나)」로 쓰면
    -- <b>사유만 매달아 둔 행이 통과한다</b> — 한쪽이 비어서 오른쪽이 거짓이 되고
    -- 상태도 거짓이라 등식이 맞아 버린다. 안 내렸는데 「광고라서 내렸다」가 붙어 있는 행이다.
    constraint inquiry_block_check
        check ((status = 'blocked') = (blocked_at is not null)
               and (blocked_at is not null) = (blocked_reason is not null)),

    -- 법이 인정한 사유만 담는다.
    --
    -- advertisement 가 제50조의7 의 자리다. abuse 는 우리 판단이고(약관), 그것을 같은 칸에
    -- 담되 값으로 갈라 둔다 — 법이 근거인 것과 우리가 정한 것이 코드에서 안 갈리면
    -- 개정될 때 무엇을 고쳐야 하는지 모른다.
    constraint inquiry_blocked_reason_check
        check (blocked_reason is null or blocked_reason in ('advertisement', 'abuse')),

    constraint inquiry_question_length_check check (length(question) between 1 and 2000),
    constraint inquiry_answer_length_check   check (answer is null or length(answer) between 1 and 2000)
);

create index inquiry_product_idx on inquiry (product_id, created_at desc)
 where product_id is not null;

create index inquiry_user_idx on inquiry (user_id, created_at desc);

-- 접수된 채 답이 안 나간 것을 찾는 자리. 처리정지·이의제기는 법이 답을 요구한다.
create index inquiry_pending_idx on inquiry (kind, created_at)
 where status = 'received';

comment on table inquiry is
    '문의. 상품 Q&A 와 계정에 붙는 법정 요구를 같이 담는다. 3년을 산다(D2 R6 시행령 제6조 4호, 청크 58)';

comment on column inquiry.kind is
    '무엇을 묻나. product 만 상품에 붙고 나머지 셋은 계정에 붙는다(R25·R28)';

comment on column inquiry.blocked_reason is
    '게시를 내린 사유. advertisement 는 정보통신망법 제50조의7, abuse 는 약관이다';
