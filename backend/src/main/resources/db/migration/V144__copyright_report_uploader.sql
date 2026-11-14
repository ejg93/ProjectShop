-- 반복 침해자 방침의 셈 단위(`Q466`, 저작권법 제102조제1항제1호다목 `D2` R42).
--
-- 상품 사진은 `product_id`(restrict)로 셀러가 남는다. 후기 사진은 `review_image_id` 가 사진 파기 때 `set null` 이라
-- 작성자 링크가 끊긴다 — 그래서 접수 때 작성자를 박제한다.
alter table copyright_report
    add column uploader_user_id bigint references app_user (user_id) on delete restrict;

comment on column copyright_report.uploader_user_id is
    '후기 사진을 올린 사람(Q466). 접수 때 박제한다 — 사진이 지워져도 반복 침해 셈이 이 사람을 가리킨다';

-- 옛 행 뒷채움. 사진이 남아 있는 것만 채워진다.
update copyright_report r
   set uploader_user_id = v.user_id
  from review_image i
  join review v on v.review_id = i.review_id
 where i.review_image_id = r.review_image_id
   and r.uploader_user_id is null;

-- 후기 사진 신고에는 작성자가 있다. **`not valid` 다** — 옛 행 중 사진이 이미 파기된 것은 작성자를 못 채운다.
-- 새 행은 이 제약을 지난다.
alter table copyright_report add constraint copyright_report_review_uploader_check
    check (target <> 'review_image' or uploader_user_id is not null) not valid;

-- 판정 트랜잭션이 12개월 안 중단 수를 센다 — 셀러 쪽은 상품으로, 작성자 쪽은 이 칸으로.
create index copyright_report_product_strike_idx
    on copyright_report (product_id, decided_at) where decision = 'taken_down';
-- 작성자 쪽은 부분 인덱스로 안 건다 — 외래키 칸이 첫 칸인 온전한 인덱스가 따로 있어야 계정 삭제 검사가 탄다(`ForeignKeyIndexTest`).
create index copyright_report_uploader_strike_idx
    on copyright_report (uploader_user_id, decided_at);
