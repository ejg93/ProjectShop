/**
 * 관리자 화면군의 바깥 틀(`Q132`).
 *
 * <p><b>셀러 틀과 값이 같고 이유도 같다</b>(`D20` 「다이얼 값」 밀도 7) — 여러 건을 한 화면에서
 * 훑는 자리라 구매자 화면(밀도 5)보다 넓고 촘촘하다.
 *
 * <p><b>그래도 파일을 하나로 안 합친다.</b> 두 화면군의 밀도가 지금 같은 것은 우연이고,
 * 합쳐 두면 한쪽을 바꿀 때 다른 쪽이 따라간다 — 경계를 코드에서 가르라는 것이 `D20` 의 요구고,
 * 같은 값을 쓰는 것과 같은 자리를 쓰는 것은 다르다.
 *
 * <p>이 틀 안의 화면은 바깥 컨테이너를 다시 안 만든다.
 */
export default function AdminLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="mx-auto grid w-full max-w-6xl flex-1 content-start gap-6 px-4 py-8">
      {children}
    </div>
  );
}
