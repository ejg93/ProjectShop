-- 저작권 신고가 후기 사진도 받는다(`Q196`, `D2` R42, 저작권법 제102조·제103조).
--
-- **공개 표면이 둘인데 신고 길이 하나였다.** `Q159` 가 이용자가 올리는 후기 사진을 열었는데 신고·게시 중단은
-- 상품 사진만 받았다 — 권리자가 후기 사진을 알려 올 자리가 없으면 그 사진에 대해 우리가 책임 제한을 못 받는다.
--
-- **무엇을 신고했는지를 칸으로 남긴다**(`target`). 사진이 지워지면 가리키는 칸은 비지만(`set null`),
-- 신고 기록은 「어느 종류의 사진이었나」를 계속 말해야 한다 — 판정 이력이 그것을 잃으면 절차를 돌렸다는
-- 증거가 반쪽이 된다. 판정 주체는 그대로다: 후기 사진도 그 상품의 공개 표면이라 `product:moderate`(관리자)가 본다.
alter table copyright_report
    add column review_image_id bigint references review_image (review_image_id) on delete set null,
    add column target text not null default 'product_image';

-- 지난 신고는 전부 상품 사진이었다. 채운 뒤 기본값을 걷는다 — 새 신고는 무엇을 신고하는지 스스로 말해야 한다.
alter table copyright_report alter column target drop default;

alter table copyright_report add constraint copyright_report_target_check
    check (target in ('product_image', 'review_image'));

-- 종류와 가리키는 칸이 맞는다. 사진이 지워져 둘 다 비는 것은 된다 — 판정은 사진이 없어도 기록한다(`V79`).
alter table copyright_report add constraint copyright_report_target_reference_check
    check ((target = 'product_image' and review_image_id is null)
        or (target = 'review_image' and product_image_id is null));

create index copyright_report_review_image_idx on copyright_report (review_image_id);

comment on column copyright_report.target is
    '무엇을 신고했나 — product_image 또는 review_image. 사진이 지워져도 남는다(Q196)';
