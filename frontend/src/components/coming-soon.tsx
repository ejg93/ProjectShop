/**
 * 지도에는 있는데 아직 안 만든 화면의 자리표시(`D20` 「아직 없는 화면은 자리표시로 둔다」).
 *
 * <p>링크를 안 걸면 화면이 생길 때마다 넣었다 뺐다 하고, 걸어 두고 404 를 주면
 * 사용자가 고장으로 본다. <b>갈 곳이 있고, 없는 것이 아니라 아직인 것이 전해져야 한다.</b>
 *
 * <p><b>이 자리표시는 그 화면을 만드는 청크가 지운다.</b> 남아 있으면 그 청크가 안 끝난 것이다.
 *
 * <p><b>{@code main} 을 안 그린다.</b> 표지는 셸이 한 번만 그린다(`D20` 「셸」) —
 * 화면마다 그리면 건너뛰기 링크가 가리키는 곳과 보조기술이 찾는 표지가 갈린다.
 */
export function ComingSoon({ title, detail }: { title: string; detail: string }) {
  return (
    <div className="mx-auto grid w-full max-w-6xl flex-1 content-center gap-2 px-4 py-16">
      <h1 className="text-2xl font-semibold tracking-tight">{title}</h1>
      <p className="text-sm text-text-muted">{detail}</p>
      <p className="text-sm text-text-muted">
        준비가 끝나면 이 자리에서 바로 쓰실 수 있습니다.
      </p>
    </div>
  );
}
