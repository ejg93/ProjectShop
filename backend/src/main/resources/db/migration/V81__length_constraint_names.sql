-- 이름 접미사가 갈린 길이 제약 여섯을 회계 안으로 들인다(`Q106`, `점검 Q` 가 찾았다).
--
-- `LengthConstraintTest` 는 `pg_constraint` 에서 이름이 `%length_check` 인 것만 걷는다.
-- `V78`·`V79` 가 접미사를 `_length` 로 달아서 **여섯이 쓸기에 아예 안 잡혔다** —
-- 「제약을 전부 걷어 대조하거나 안 하는 이유를 적는다」(`Q28`)가 이름 하나로 뚫렸다.
-- 나머지 서른아홉이 `_length_check` 라 이 여섯만 다르다(`D22` 의 접미사 표는 한 벌이다).
--
-- **정의도 같이 고친다.** 여섯 다 `not null` 인데 `<= N` 이라 빈 문자열이 들어간다 —
-- 규칙은 `V65`·`V74` 와 같다: `@NotBlank` 가 붙은 칸은 `between 1 and N` 이다.
-- 사진 셋은 요청 칸이 아니라 앱이 만들어 넣는 값이고, 빈 파일 이름은 업로드 입구가
-- 확장자 검사에서 먼저 415 로 끊는다(`ProductImageService.requireMatchingExtension`).
--
-- **값은 하나도 안 바꾼다.** 이름과 빈 문자열 허용 여부만 고치는 마이그레이션이라,
-- 수를 같이 건드리면 어느 쪽이 무엇을 고친 것인지 다음 사람이 못 가른다.

alter table product_image
    drop constraint product_image_object_key_length,
    drop constraint product_image_thumbnail_key_length,
    drop constraint product_image_original_name_length,
    -- 열쇠는 앱이 만들어서 모양이 정해져 있다(`product/{UUID}/{이름}.{확장자}`).
    -- 200 은 그 모양의 두 배로 잡은 값이다(`V78` 에서 옮겨 왔다).
    add constraint product_image_object_key_length_check
        check (length(object_key) between 1 and 200),
    add constraint product_image_thumbnail_key_length_check
        check (length(thumbnail_key) between 1 and 200),
    -- 255 는 흔한 파일 시스템의 이름 상한이다(`V78` 에서 옮겨 왔다).
    add constraint product_image_original_name_length_check
        check (length(original_name) between 1 and 255);

alter table copyright_report
    drop constraint copyright_report_reporter_name_length,
    drop constraint copyright_report_reporter_email_length,
    drop constraint copyright_report_claimed_work_length,
    -- 셋 다 입구의 `@Size` 와 같은 수다(`V79` 에서 옮겨 왔다).
    -- 갈리면 한쪽이 받은 것을 다른 쪽이 거절해서 500 이 난다.
    add constraint copyright_report_reporter_name_length_check
        check (length(reporter_name) between 1 and 100),
    add constraint copyright_report_reporter_email_length_check
        check (length(reporter_email) between 1 and 320),
    add constraint copyright_report_claimed_work_length_check
        check (length(claimed_work) between 1 and 2000);
