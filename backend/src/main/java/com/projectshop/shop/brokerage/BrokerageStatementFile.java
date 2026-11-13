package com.projectshop.shop.brokerage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;

import com.projectshop.shop.payment.PaymentMethod;

/**
 * 중개 명세 한 분기를 별지 제48호서식 항목 순서의 CSV 로 쓴다(`Q456`, 부가가치세법 시행규칙 제78조 `D2` R46).
 *
 * <p><b>열은 서식 ⑤~⑰ 순서다</b> — 일련번호 · 판매대행 연월 · 결제구분 코드 · 건수 · 금액(부가세 포함) · 의뢰자 신원 여덟.
 * 모르는 칸(생년월일·아이디·휴대전화)은 비운다 — 서식 작성방법이 「알지 못하는 항목은 적지 않습니다」라 한다.
 * 홈택스가 받는 전산파일의 실제 배치는 국세청 제출요령이 정하는데 못 구했다 — 다르면 이 클래스 하나만 고친다.
 *
 * <p><b>수식 주입을 막는다.</b> 모든 칸을 큰따옴표로 싸고, {@code =}·{@code +}·{@code -}·{@code @}·탭·CR 로 시작하는 값 앞에 작은따옴표를 붙인다 —
 * 셀러가 정한 상호가 스프레드시트에서 수식으로 실행되면 안 된다(OWASP CSV Injection). UTF-8, 줄 끝은 CRLF(RFC 4180).
 */
final class BrokerageStatementFile {

    /** 머리 줄 — 서식 ⑤~⑰ */
    private static final List<String> HEADER = List.of(
            "일련번호", "판매대행연월", "결제구분", "건수", "금액",
            "사업자등록번호", "상호", "성명", "관리번호", "생년월일", "아이디", "전화번호", "휴대전화번호");

    private BrokerageStatementFile() {
    }

    static byte[] render(List<Row> rows) {
        StringBuilder out = new StringBuilder();
        line(out, HEADER);
        int serial = 1;
        for (Row row : rows) {
            line(out, List.of(
                    Integer.toString(serial++),
                    "%04d%02d".formatted(row.salesMonth().getYear(), row.salesMonth().getMonthValue()),
                    formCode(row.method()),
                    Integer.toString(row.saleCount()),
                    Long.toString(row.saleAmount()),
                    nullToEmpty(row.businessRegNo()),
                    nullToEmpty(row.businessName()),
                    nullToEmpty(row.representativeName()),
                    Long.toString(row.sellerId()),
                    "",
                    "",
                    nullToEmpty(row.phone()),
                    ""));
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    static String sha256Hex(byte[] file) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 이 없다", e);
        }
    }

    /**
     * 서식의 결제구분 코드. 우리 결제수단을 여기 한 곳에서 바꾼다 — DB 에는 우리 값을 둔다.
     * 이체는 「R 기타」다 — 현금영수증을 안 내니 「A 현금영수증 발급」이 아니다.
     */
    private static String formCode(PaymentMethod method) {
        return switch (method) {
            case CARD -> "C";
            case TRANSFER -> "R";
        };
    }

    private static void line(StringBuilder out, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append('"').append(neutralize(cells.get(i)).replace("\"", "\"\"")).append('"');
        }
        out.append("\r\n");
    }

    private static String neutralize(String value) {
        return !value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 한 줄 — 명세 수치와 내려받는 순간의 셀러 신원 */
    record Row(LocalDate salesMonth, PaymentMethod method, int saleCount, long saleAmount, long sellerId,
            String businessRegNo, String businessName, String representativeName, String phone) {
    }
}
