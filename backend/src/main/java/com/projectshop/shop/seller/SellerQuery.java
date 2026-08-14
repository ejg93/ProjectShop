package com.projectshop.shop.seller;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

/**
 * 셀러를 찾아 본다. 지금은 <b>법이 표시를 요구하는 신원 정보</b> 하나뿐이다(`D2` R1).
 *
 * <p>전자상거래법 제10조·제13조가 상호·대표자·주소·전화·전자우편·사업자등록번호·신고번호를 요구하고,
 * 제20조제2항이 중개자에게 <b>청약이 이루어지기 전까지</b> 그것을 제공하라고 한다.
 * 그래서 이 값은 <b>로그인 없이 열린다</b> — 살 사람은 사기 전에 보므로 대개 비로그인이다.
 *
 * <p><b>상품 상세에 중첩하지 않고 경로를 갈랐다.</b> 이 단위를 부르는 곳이 넷이다 —
 * 상품 상세(`14b`), 주문서(`15`, 법이 말하는 시점이 거기다), 소비자 요청 시 열람(R1),
 * 나중의 셀러 상점. 중첩하면 그 넷 중 하나가 상품 없이 물을 때 계약이 두 벌이 된다.
 */
@Service
public class SellerQuery {

    private final JdbcClient jdbc;

    SellerQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 파는 셀러의 신원 정보.
     *
     * <p>조건이 상품 공개 조회와 같은 성격이다 — <b>파는 중이고 살아 있는 것</b>.
     * {@code pending} 셀러는 신원 칸이 비어 있을 수 있고(`3c` 의 {@code check} 가
     * {@code active} 에만 걸린다), 그런 행을 내리면 화면이 빈 표를 그린다.
     *
     * <p><b>폐업한 셀러는 여기서 안 나온다.</b> 이미 산 사람이 파는 사람을 확인해야 하는 경로가
     * 따로 있는데(R1 의 열람 제공), 그건 자기 주문을 통해 묻는 것이라 인증이 붙는다 —
     * 이 경로를 열어 두면 폐업 정보가 아무에게나 나간다. 그 경로는 주문 상세(`15`)가 만든다.
     */
    public PublicIdentity findPublicIdentity(long sellerId) {
        return jdbc.sql("""
                        select seller_id, name, business_name, representative_name,
                               business_reg_no, address, phone, email,
                               mail_order_no, mail_order_exempt_reason
                          from seller
                         where seller_id = :id
                           and status = 'active' and deleted_at is null
                        """)
                .param("id", sellerId)
                .query((rs, rowNum) -> new PublicIdentity(
                        rs.getLong("seller_id"),
                        rs.getString("name"),
                        rs.getString("business_name"),
                        rs.getString("representative_name"),
                        rs.getString("business_reg_no"),
                        rs.getString("address"),
                        rs.getString("phone"),
                        rs.getString("email"),
                        rs.getString("mail_order_no"),
                        MailOrderExemption.of(rs.getString("mail_order_exempt_reason"))))
                .optional()
                // 아직 안 파는 셀러와 아예 없는 셀러가 같은 404 다. 상품 상세와 같은 이유로 안 가른다.
                .orElseThrow(() -> new ShopException(ErrorCode.SELLER_NOT_FOUND));
    }

    /**
     * 누구에게나 같은 값. <b>수수료율·기본 배송비가 없다</b> — 우리와 셀러 사이의 조건이라
     * 사는 사람이 볼 것이 아니고, 한 record 로 만들면 마스킹을 또 붙이게 된다.
     *
     * @param name 화면에 쓰는 이름. {@code businessName} 은 법정 상호라 다를 수 있다
     * @param businessRegNo 숫자 10자리다. <b>하이픈은 화면이 넣는다</b> — 저장 형식을 안 바꾼다
     * @param mailOrderNo 통신판매업 신고번호. 면제면 {@code null} 이고 그때 아래가 채워진다
     * @param mailOrderExemptReason 신고를 안 해도 되는 사유. 신고번호가 있으면 {@code null}.
     *                              <b>둘 다 채워지는 일은 없다</b>({@code seller_mail_order_check})
     */
    public record PublicIdentity(long sellerId, String name, String businessName,
            String representativeName, String businessRegNo, String address, String phone,
            String email, String mailOrderNo, MailOrderExemption mailOrderExemptReason) {
    }
}
