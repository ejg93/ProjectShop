-- V109 을 되돌린다. 후기 사진 신고 행은 되돌릴 자리가 없어서 지운다 — 상품 사진 신고만 남는 표로 돌아간다.

delete from copyright_report where target = 'review_image';

drop index copyright_report_review_image_idx;
alter table copyright_report drop constraint copyright_report_target_reference_check;
alter table copyright_report drop constraint copyright_report_target_check;
alter table copyright_report
    drop column target,
    drop column review_image_id;
