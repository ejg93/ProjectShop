package com.projectshop.shop.payment;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 진짜 PG 자리에 서 있는 모의 결제사.
 *
 * <p><b>이 클래스가 흉내내는 것은 승인 여부가 아니라 계약이다.</b> 결과를 무작위로 내면
 * 테스트가 흔들리고, 늘 승인하면 거절 경로가 한 번도 안 돈다. 그래서 결과를
 * <b>카드번호 뒷 4자리로 가른다</b> — Stripe·Toss 의 테스트 카드와 같은 방식이고,
 * 화면에서 손으로 밟을 때도 번호만 바꾸면 된다.
 *
 * <table>
 *   <caption>테스트 카드</caption>
 *   <tr><th>뒷 4자리</th><th>결과</th></tr>
 *   <tr><td>{@code 0000}</td><td>거절({@code limit_exceeded})</td></tr>
 *   <tr><td>{@code 0002}</td><td>첫 호출은 응답 없음. 다시 부르면 승인</td></tr>
 *   <tr><td>그 밖</td><td>승인</td></tr>
 * </table>
 *
 * <p><b>멱등키를 받는 것이 이 모듈의 핵심 계약이다.</b> 우리는 PG 호출을 트랜잭션 밖에서 하므로
 * (`D11` 「트랜잭션 경계」) 재전송이 같은 카드에 두 번 승인을 낼 수 있는 구간이 열린다.
 * 그 구간을 닫는 것은 우리 트랜잭션이 아니라 <b>PG 가 같은 키에 같은 결과를 주는 것</b>이다.
 * 실물 PG 도 같은 이유로 멱등키를 받는다.
 *
 * <p><b>키 기억은 메모리라 재기동하면 사라진다.</b> 알고 남기는 구멍이다 —
 * 모의 결제사에 표를 파면 우리 DB 가 남의 시스템 상태를 들고 있게 돼서
 * 진짜 PG 로 갈아끼울 때 그 표가 갈 곳이 없다. 대신 우리 쪽 중복은
 * {@code payment_approved_unique} 가 끝에서 한 번 더 막는다.
 */
@Component
public class MockPaymentGateway {

    /** 한도 초과로 거절할 카드 */
    private static final String DECLINE_LAST4 = "0000";

    /** 첫 호출이 응답 없이 끊기는 카드. 재시도가 실제로 도는지 보는 자리다 */
    private static final String TIMEOUT_LAST4 = "0002";

    private static final String DECLINE_REASON = "limit_exceeded";

    /** ISO/IEC 7812 이 정한 카드번호 길이 */
    private static final int MIN_DIGITS = 12;
    private static final int MAX_DIGITS = 19;

    /** 앞 한 자리가 카드 브랜드다. 모르는 것은 기타로 둔다 */
    private static final Map<Character, String> ISSUERS = Map.of(
            '3', "아멕스",
            '4', "비자",
            '5', "마스터",
            '9', "국내전용");

    private static final String UNKNOWN_ISSUER = "기타";

    private final SecureRandom random = new SecureRandom();

    /** 같은 키에 같은 답을 주기 위한 기억 */
    private final Map<String, Result> byKey = new ConcurrentHashMap<>();

    /** 타임아웃 카드가 몇 번째 호출인지 */
    private final Map<String, Integer> attempts = new ConcurrentHashMap<>();

    /**
     * 결제사에 보내는 것.
     *
     * <p><b>카드번호가 여기까지만 온다.</b> 이 record 는 저장되지 않고 우리 표 어디에도 안 닿는다
     * (`D2` R18). 남는 것은 {@link Result} 가 돌려주는 뒷 4자리뿐이다.
     */
    public record Request(String idempotencyKey, long amount, String method, String cardNumber) {}

    /**
     * 결제사가 준 것.
     *
     * @param approvalNumber 승인번호. 거절이면 {@code null}
     * @param declineReason  거절 사유 코드. 승인이면 {@code null}
     */
    public record Result(String approvalNumber, String cardIssuer, String cardLast4,
            String declineReason) {

        public boolean approved() {
            return approvalNumber != null;
        }
    }

    /**
     * 결제사가 제때 답을 안 줬다.
     *
     * <p><b>{@code ErrorCode} 에 안 넣는다.</b> 그 목록은 밖으로 나가는 오류고 이건 안 나간다 —
     * 재시도가 흡수하거나, 다 쓰면 {@link PaymentService} 가 그때 나갈 코드로 번역한다.
     */
    public static class TimedOut extends RuntimeException {

        TimedOut(String message) {
            super(message);
        }
    }

    /**
     * 승인을 요청한다.
     *
     * @throws TimedOut                 응답이 없을 때. 같은 키로 다시 부르는 것이 안전하다
     * @throws IllegalArgumentException 카드번호 형식이 아닐 때. 입구가 이미 거르므로 여기 오면 우리 잘못이다
     */
    public Result approve(Request request) {
        Result remembered = byKey.get(request.idempotencyKey());
        if (remembered != null) {
            return remembered;
        }

        Result result = judge(request);
        byKey.put(request.idempotencyKey(), result);
        return result;
    }

    /**
     * 환불 결과.
     *
     * @param refundNumber 결제사가 채번한 환불 거래번호
     */
    public record RefundResult(String refundNumber) {}

    /** 이미 돌려준 환불 요청. 키는 우리 환불번호다 */
    private final Map<String, RefundResult> refundsByKey = new ConcurrentHashMap<>();

    /**
     * 승인된 결제의 일부 또는 전부를 돌려준다.
     *
     * <p><b>거절이 없다.</b> 결제 승인과 다른 점이다 — 승인은 카드 한도·유효성 판정이라 거절이
     * 정상 결과지만, 환불은 이미 받은 돈을 돌려주는 것이라 결제사가 거부할 사유가 없다.
     * 실물 PG 도 원거래가 살아 있으면 거절하지 않는다. <b>상한 검사는 우리 쪽 몫</b>이고
     * {@code assert_refund_within_payment} 가 그것을 본다.
     *
     * <p><b>멱등키는 우리 환불번호다.</b> 승인과 같은 이유로 필요하다 — PG 호출이 트랜잭션 밖이라
     * (`D11`) 재시도가 두 번 환불하는 구간이 열리고, 그 구간을 닫는 것은 PG 가 같은 키에
     * 같은 답을 주는 것이다. 승인은 클라이언트가 만든 키를 그대로 넘겼는데 환불은
     * <b>요청 자체가 우리 자원</b>이라 그 번호가 곧 키다.
     *
     * @throws TimedOut 응답이 없을 때. 같은 환불번호로 다시 부르는 것이 안전하다
     */
    public RefundResult refund(String refundNumber, long amount) {
        RefundResult remembered = refundsByKey.get(refundNumber);
        if (remembered != null) {
            return remembered;
        }

        if (amount <= 0) {
            throw new IllegalArgumentException("환불 금액이 0 이하다");
        }

        RefundResult result = new RefundResult("MR%d%04d"
                .formatted(System.currentTimeMillis(), random.nextInt(10_000)));

        refundsByKey.put(refundNumber, result);
        return result;
    }

    private Result judge(Request request) {
        if (!"card".equals(request.method())) {
            // 계좌이체는 카드정보가 없다. 실패를 유도할 자리도 없어서 늘 승인이다
            return new Result(issueApprovalNumber(), null, null, null);
        }

        String digits = digitsOf(request.cardNumber());
        String last4 = digits.substring(digits.length() - 4);

        if (TIMEOUT_LAST4.equals(last4) && firstAttempt(request.idempotencyKey())) {
            throw new TimedOut("결제사가 응답하지 않는다");
        }
        if (DECLINE_LAST4.equals(last4)) {
            return new Result(null, issuerOf(digits), last4, DECLINE_REASON);
        }
        return new Result(issueApprovalNumber(), issuerOf(digits), last4, null);
    }

    /**
     * 이 키로 처음 부르는가.
     *
     * <p>키마다 센다. 요청마다 세면 <b>재시도 없이 요청을 두 번 보낸 클라이언트가 승인을 받아서</b>
     * 이 카드가 무엇을 확인하려는 것인지 사라진다.
     */
    private boolean firstAttempt(String idempotencyKey) {
        return attempts.merge(idempotencyKey, 1, Integer::sum) == 1;
    }

    /**
     * 승인번호를 채번한다. 진짜 PG 는 자기 규칙으로 만들고 우리는 그것을 그대로 받는다.
     *
     * <p>시각을 앞에 두는 이유는 <b>재기동해도 안 겹치게</b> 하려는 것이다.
     * 일련번호만 쓰면 다시 뜬 뒤 1번부터라 {@code payment_approval_number_unique} 에 걸린다.
     */
    private String issueApprovalNumber() {
        return "M%d%04d".formatted(System.currentTimeMillis(), random.nextInt(10_000));
    }

    private static String issuerOf(String digits) {
        return ISSUERS.getOrDefault(digits.charAt(0), UNKNOWN_ISSUER);
    }

    /** 하이픈과 공백은 사람이 읽으라고 넣은 것이라 걷어낸다 */
    private static String digitsOf(String cardNumber) {
        String digits = cardNumber == null ? "" : cardNumber.replaceAll("[\\s-]", "");

        if (!digits.matches("[0-9]{%d,%d}".formatted(MIN_DIGITS, MAX_DIGITS))) {
            throw new IllegalArgumentException("카드번호 형식이 아니다");
        }
        return digits;
    }
}
