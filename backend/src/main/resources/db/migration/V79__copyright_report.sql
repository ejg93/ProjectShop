-- 남의 저작물 신고를 받는다(`Q94`, `D2` `R42`).
--
-- **저작권법 제102조·제103조가 요구하는 절차다.** 신고를 받고 처리하는 자리가 있어야
-- 온라인서비스제공자 책임 제한을 받는다. 그 절차가 없으면 셀러가 올린 남의 사진에
-- 우리가 같이 책임진다.
--
-- **표가 append-only 다.** 신고는 접수된 사실 자체가 증거라 지우지 않는다 —
-- 처리 결과는 `decided_at`·`decision` 으로 덧쓴다(`D13` 과 같은 모양).

create table copyright_report (
    copyright_report_id bigint not null generated always as identity primary key,

    -- 신고 대상. **비어 있을 수 있다** — 게시 중단이 그 사진 행을 지우기 때문이다.
    --
    -- restrict 로 두면 「지우라는 신고」가 「지우면 안 되는 제약」이 돼서 서로 막는다.
    -- cascade 로 두면 지우는 순간 신고 기록이 같이 사라져서 **절차를 돌린 증거가 없어진다.**
    -- 남는 길은 set null 뿐이고, 무엇에 대한 신고였는지는 아래 product_id 가 든다.
    product_image_id bigint
        references product_image (product_image_id) on delete set null,

    -- 어느 상품에 대한 신고였나. 사진이 사라져도 이건 남는다 —
    -- 신고 기록이 가리킬 것이 하나도 없으면 「무엇을 내렸나」를 나중에 못 답한다.
    product_id bigint not null references product (product_id) on delete restrict,

    -- **로그인을 요구하지 않는다.** 저작권자가 우리 회원일 이유가 없다 —
    -- 회원만 신고할 수 있게 하면 법이 요구한 절차에 가입이라는 관문이 하나 붙는다.
    -- 그래서 신고자는 계정이 아니라 연락처로 받는다.
    reporter_name  text not null,
    reporter_email text not null,

    -- 무엇에 대한 권리인지. 법이 「저작물을 특정할 수 있는 정보」를 요구한다.
    claimed_work text not null,

    reported_at timestamptz not null default now(),

    -- 판정. 비어 있으면 아직 안 봤다는 뜻이다.
    --
    -- **`null` 에 뜻을 싣는 것이 아니다**(`D23`) — 「판정이 없다」와 「접수 상태다」는
    -- 같은 말이고, 상태를 따로 두면 둘이 어긋날 자리가 생긴다.
    decision text,
    decided_at timestamptz,
    decided_by_user_id bigint references app_user (user_id) on delete restrict,

    -- 길이 상한은 앱과 DB 양쪽에 둔다(coding-rules.md). 값은 입구의 @Size 와 같다 —
    -- 갈리면 한쪽이 받은 것을 다른 쪽이 거절해서 500 이 난다.
    constraint copyright_report_reporter_name_length check (length(reporter_name) <= 100),
    constraint copyright_report_reporter_email_length check (length(reporter_email) <= 320),
    constraint copyright_report_claimed_work_length check (length(claimed_work) <= 2000),

    -- 법이 인정한 결과만 들어간다(`coding-rules.md` 「법이 인정한 목록은 닫는다」).
    -- 자유 텍스트로 두면 법이 인정하지 않는 처리가 기록으로 남는다.
    constraint copyright_report_decision_check
        check (decision is null or decision in ('taken_down', 'rejected')),

    -- 판정 셋이 같이 차거나 같이 빈다. 하나만 차면 「누가 언제 무엇을」 중 하나가 없다.
    constraint copyright_report_decision_complete
        check (num_nulls(decision, decided_at, decided_by_user_id) in (0, 3))
);

-- 관리자가 안 본 것부터 읽는다.
create index copyright_report_pending on copyright_report (reported_at)
    where decided_at is null;

-- ---------------------------------------------------------------------------
-- 판정 권한
-- ---------------------------------------------------------------------------

-- **기존 `product:delete` 를 안 쓴다.** 그것은 `seller_owner` 가 `seller` 스코프로 갖고 있어서,
-- 쓰면 **셀러가 자기 상품에 들어온 신고를 스스로 판정한다.** 신고의 상대가 판정하는 구조다.
--
-- 그래서 동작을 하나 판다. **관리자에게만 준다** — `V3` 의 「관리자는 permission 전체를
-- all 스코프로 받는다」는 그때 있던 권한만 덮으므로, 새 권한은 그 청크가 붙인다.
insert into permission (resource, action, description, kind)
values ('product', 'moderate', '신고된 게시물을 내린다', 'write');

insert into role_permission (role_id, permission_id, scope)
select r.role_id, p.permission_id, 'all'
from permission p
join role r on r.code = 'admin'
where p.resource = 'product' and p.action = 'moderate';
