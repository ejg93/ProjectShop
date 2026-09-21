"use client";

import { useRouter } from "next/navigation";
import { useRef, useState, useTransition } from "react";

import { ApiError, api, apiUpload } from "@/lib/api";

/** 서버가 내주는 사진 한 장(`ProductImageService.Image`) */
export type ProductImage = {
  productImageId: number;
  thumbnailUrl: string;
  originalName: string;
  sortNo: number;
};

/**
 * 받는 형식. <b>서버가 인정하는 것과 같게 둔다</b>(`ImageContentType`) — 넓게 적으면
 * 고를 수는 있는데 올리면 거부되고, 좁게 적으면 올릴 수 있는 것을 못 고른다.
 */
const ACCEPT = "image/jpeg,image/png";

/**
 * 사진을 올리고 지운다(`Q140`).
 *
 * <p><b>화면이 판정을 다시 하지 않는다</b>(`D5`). 남의 상품·5MB 초과·열 장 초과·형식 넷을
 * 서버가 이미 막고 있어서, 여기서 같은 규칙을 한 벌 더 들면 <b>두 곳이 갈리는 날</b>이 온다.
 * 대신 거부의 <b>이유</b>를 사람 말로 옮긴다.
 *
 * <p><b>고르면 바로 올린다.</b> 「고르기 → 올리기」 두 단추는 고른 뒤 안 누르고 떠나는 자리를 만든다 —
 * 파일 하나짜리 입력에서는 고른 것이 곧 의도다.
 *
 * <p><b>결과를 화면이 직접 안 그린다.</b> {@code router.refresh()} 로 서버가 다시 그린다 —
 * 정렬 번호와 대표 이미지는 서버가 정하므로, 화면에서 이어 붙이면 그 값이 갈린다.
 */
export function ImageManager({
  productId,
  images,
}: {
  productId: number;
  images: ProductImage[];
}) {
  const router = useRouter();
  const picker = useRef<HTMLInputElement>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [sending, setSending] = useState(false);
  const [refreshing, startRefresh] = useTransition();
  const busy = sending || refreshing;

  async function send(action: () => Promise<void>, done: string) {
    setSending(true);
    setFailure(null);
    setNotice(null);
    try {
      await action();
      setNotice(done);
      startRefresh(() => router.refresh());
    } catch (error) {
      setFailure(messageFor(error));
    } finally {
      setSending(false);
    }
  }

  function upload(file: File | undefined) {
    if (!file) {
      return;
    }
    void send(
      async () => {
        await apiUpload(`/api/seller/products/${productId}/images`, file);
        // 같은 파일을 다시 고를 수 있게 비운다. 안 비우면 `change` 가 안 나서
        // 두 번째 시도가 아무 일도 안 하는 것처럼 보인다.
        if (picker.current) {
          picker.current.value = "";
        }
      },
      `${file.name}을(를) 올렸습니다.`,
    );
  }

  const remove = (image: ProductImage) =>
    send(
      () => api(`/api/seller/products/images/${image.productImageId}`, { method: "DELETE" }),
      `${image.originalName}을(를) 지웠습니다.`,
    );

  return (
    <div className="grid gap-4">
      <div className="grid gap-2">
        <label htmlFor="product-image" className="text-sm font-semibold">
          사진 올리기
        </label>
        {/*
          도움말을 입력 앞에 둔다(WCAG 3.3.2). 거부된 뒤에 조건을 알려 주면 그 시도는 이미 버려진 것이다.
        */}
        <p id="product-image-hint" className="text-xs text-text-muted">
          JPG 또는 PNG, 한 장에 5MB까지, 상품마다 10장까지 올릴 수 있습니다.
        </p>
        <input
          ref={picker}
          id="product-image"
          name="file"
          type="file"
          accept={ACCEPT}
          disabled={busy}
          aria-describedby="product-image-hint"
          onChange={(event) => upload(event.target.files?.[0])}
          className="
            text-sm
            focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
            disabled:opacity-60
          "
        />
      </div>

      {/*
        결과를 살아 있는 영역에 둔다(WCAG 4.1.3). 초점이 파일 입력에 남아 있어서
        읽어 주는 장치는 이 자리가 바뀐 것을 스스로 못 안다.
      */}
      <div aria-live="polite" className="grid gap-1 empty:hidden">
        {failure ? <p className="text-sm text-danger-text">{failure}</p> : null}
        {notice ? <p className="text-sm text-text-muted">{notice}</p> : null}
      </div>

      {images.length === 0 ? (
        <p className="text-sm text-text-muted">
          아직 올린 사진이 없습니다. 사진이 없으면 목록에 대신 이미지가 나갑니다.
        </p>
      ) : (
        <ul className="grid gap-2">
          {images.map((image) => (
            <li
              key={image.productImageId}
              className="flex items-center justify-between gap-3 border-b border-border pb-2"
            >
              <div className="flex items-center gap-3">
                {/*
                  대체 텍스트를 파일 이름으로 안 쓴다(WCAG 1.1.1). 여기서 사진은 「무엇이 걸려 있나」를
                  가리키는 미리보기라, 순서가 곧 그 뜻이다.
                */}
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img
                  src={image.thumbnailUrl}
                  alt={`${image.sortNo + 1}번째 사진 미리보기`}
                  className="h-16 w-16 rounded border border-border object-cover"
                />
                <span className="text-sm">{image.originalName}</span>
              </div>

              <button
                type="button"
                onClick={() => remove(image)}
                disabled={busy}
                className="
                  rounded border border-border px-3 py-1 text-sm
                  hover:text-danger-text
                  focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
                  disabled:opacity-60
                "
              >
                지우기
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function messageFor(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return "잠시 후 다시 시도해 주세요.";
  }

  switch (error.slug) {
    case "image-too-large":
      return "사진 한 장은 5MB까지 올릴 수 있습니다. 더 작은 파일로 다시 시도해 주세요.";
    case "image-type-not-allowed":
      return "JPG 또는 PNG 파일만 올릴 수 있습니다.";
    case "image-limit-reached":
      return "상품마다 사진은 10장까지입니다. 먼저 한 장을 지워 주세요.";
    case "product-forbidden":
      return "내 상품이 아닙니다.";
    case "product-not-found":
      return "없는 상품입니다. 목록에서 다시 골라 주세요.";
    default:
      return "사진을 바꾸지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
}
