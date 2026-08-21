/**
 * 셀러 화면군의 바깥 틀(`13c-1`).
 *
 * <p><b>밀도가 여기서 갈린다</b>(`D20` 「다이얼 값」). `VISUAL_DENSITY` 가 구매자 5, 셀러 7 이고
 * 두 화면군의 목적이 반대라서다 — 구매자 화면은 상품 하나에 집중시키고, 셀러 화면은
 * 주문 여러 건을 한 화면에서 처리한다. 같은 간격을 주면 한쪽이 틀린다.
 *
 * <p>`D20` 이 <b>「경계를 코드에서 가른다」</b>고 정해 뒀는데 그 경계가 없었다.
 * 없으면 화면마다 간격을 다시 정하게 되고, <b>화면마다 정하면 화면마다 달라진다.</b>
 *
 * <p><b>그래서 이 틀 안의 화면은 바깥 컨테이너를 다시 안 만든다.</b>
 * 자기 폭·여백을 또 잡으면 이 파일이 아무것도 안 가르는 것이 된다.
 *
 * <dl>
 *   <dt>{@code max-w-6xl}</dt><dd>구매자보다 넓다. 한 줄에 칸이 여럿인 표를 그린다</dd>
 *   <dt>{@code py-8}·{@code gap-6}</dt><dd>구매자의 {@code py-16}·{@code gap-8} 보다 좁다</dd>
 * </dl>
 *
 * <p>경로 접두어가 이미 {@code /seller} 라 라우트 그룹을 따로 안 판다 —
 * 주소에 안 드러나는 묶음이 필요할 때 쓰는 것이고, 여기는 주소가 이미 갈라져 있다.
 */
export default function SellerLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="mx-auto grid w-full max-w-6xl flex-1 content-start gap-6 px-4 py-8">
      {children}
    </div>
  );
}
