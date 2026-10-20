package com.projectshop.shop.inquiry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.FieldGroup;
import com.projectshop.shop.auth.PersonalFieldGroup;

/**
 * 문의 필드 그룹 두 목록이 {@code permission_field_group} 표와 같은지 본다(마무리 65차 독립 리뷰).
 *
 * <p>{@code auth.FieldGroupTest} 와 같은 대조다. 문의 목록은 패키지 안 열거형이라 그쪽에서 못 보고, 그래서
 * {@code EnumConstraintTest} 가 「{@code FieldGroupTest} 가 대조한다」고 면제해 둔 채 대조가 없었다.
 * 어긋나면 조용하다 — 코드에만 있으면 그 칸이 늘 빠지고, 표에만 있으면 아무도 안 묻는다.
 */
@DisplayName("문의 필드 그룹 목록")
class InquiryFieldGroupTest extends PostgresTestBase {

    @Autowired
    private JdbcClient jdbc;

    @Test
    @DisplayName("문의 필드 그룹이 표와 같다")
    void inquiryFieldsMatchTable() {
        List<String> codes = jdbc.sql("select code from permission_field_group where resource = 'inquiry'")
                .query(String.class)
                .list();

        assertThat(codes).containsExactlyInAnyOrderElementsOf(Stream.concat(
                Arrays.stream(InquiryFields.values()).map(FieldGroup::code),
                Arrays.stream(InquiryPersonalFields.values()).map(PersonalFieldGroup::code)).toList());
    }
}
