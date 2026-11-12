-- `check` 이름 넷을 `_check` 꼴로 맞춘다(`Q427`, `naming-rules.md` 「`check` 제약 이름은 `_check` 로 끝난다」).
--
-- **왜**: 길이 회계(`LengthConstraintTest`)는 `%length!_check` 로, 값 목록 회계(`EnumConstraintTest`)는 이름으로 제약을 걷는다.
-- `V100` 의 리뷰 사진 길이 셋은 `_length` 로 끝나 길이 대조를 비껴갔고, `V79` 의 판정 셋 제약도 꼴 밖이었다.
-- 이름만 바꾼다 — 조건은 그대로라 기존 행을 다시 안 잰다. 넷 다 시험·문서·main 코드가 이름을 안 불렀다.
alter table review_image rename constraint review_image_object_key_length to review_image_object_key_length_check;
alter table review_image rename constraint review_image_thumbnail_key_length to review_image_thumbnail_key_length_check;
alter table review_image rename constraint review_image_original_name_length to review_image_original_name_length_check;
alter table copyright_report rename constraint copyright_report_decision_complete to copyright_report_decision_complete_check;
