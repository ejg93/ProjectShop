package com.projectshop.shop.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 장부·증빙 보존 만료일(`D2` R41).
 *
 * <p>DB 를 안 띄운다 — 날짜 계산이라 값만 넣으면 답이 나온다(`D15`).
 *
 * <p><b>이 수치가 법에서 왔다</b>(국세기본법 제85조의3제2항 · 부가가치세법 제71조제3항·제49조제1항).
 * 틀리면 <b>보존해야 할 장부를 일찍 지운다</b> — 그래서 경계를 값으로 박아 둔다.
 */
@DisplayName("세법 보존 만료일")
class TaxRetentionTest {

    @Test
    @DisplayName("상반기 거래는 그해 7월 25일 다음날부터 5년이다")
    void countsFromTheJulyDeadlineForTheFirstHalf() {
        assertThat(TaxRetention.retainUntil(LocalDate.of(2026, 3, 14)))
                .as("1기(1~6월) 확정신고기한이 7월 25일이고 기산은 그 다음날이다")
                .isEqualTo(LocalDate.of(2031, 7, 26));
    }

    @Test
    @DisplayName("하반기 거래는 다음 해 1월 25일 다음날부터 5년이다")
    void countsFromTheJanuaryDeadlineForTheSecondHalf() {
        assertThat(TaxRetention.retainUntil(LocalDate.of(2026, 9, 16)))
                .as("2기(7~12월) 확정신고기한은 다음 해 1월 25일이다")
                .isEqualTo(LocalDate.of(2032, 1, 26));
    }

    @Test
    @DisplayName("6월 말과 7월 초가 반년을 사이에 두고 갈린다")
    void splitsAtTheEndOfJune() {
        LocalDate lastOfFirstHalf = TaxRetention.retainUntil(LocalDate.of(2026, 6, 30));
        LocalDate firstOfSecondHalf = TaxRetention.retainUntil(LocalDate.of(2026, 7, 1));

        assertThat(lastOfFirstHalf).isEqualTo(LocalDate.of(2031, 7, 26));
        assertThat(firstOfSecondHalf)
                .as("하루 차이로 과세기간이 갈리고 만료일이 반년 벌어진다")
                .isEqualTo(LocalDate.of(2032, 1, 26));
    }

    @Test
    @DisplayName("12월 거래는 지급이 다음 해라도 그해 2기로 잰다")
    void usesTheTransactionDateNotThePayoutDate() {
        LocalDate byTransaction = TaxRetention.retainUntil(LocalDate.of(2026, 12, 31));
        LocalDate byPayout = TaxRetention.retainUntil(LocalDate.of(2027, 1, 10));

        assertThat(byTransaction).isEqualTo(LocalDate.of(2032, 1, 26));
        assertThat(byPayout)
                .as("지급일로 재면 다음 해 1기라 만료가 반년 늦다. 필요 없이 오래 두는 것도"
                        + " 개인정보법 제21조제1항이 막는다")
                .isEqualTo(LocalDate.of(2032, 7, 26));
    }
}
