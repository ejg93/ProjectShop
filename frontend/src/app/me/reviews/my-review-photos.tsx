"use client";

import { useRouter } from "next/navigation";
import { useRef, useState, useTransition } from "react";

import { ApiError, api, apiUpload } from "@/lib/api";

const ACCEPT = "image/jpeg,image/png";

export type MyReviewPhoto = { reviewImageId: number; thumbnailUrl: string };

/**
 * 내 후기에 사진을 붙이고 뗀다(`Q174`).
 *
 * <p><b>공개 게시물이다</b>(`Q159` 사용자 결정) — 후기와 함께 누구에게나 보인다. 그래서 올리는 칸 <b>앞에</b>
 * 개인정보를 올리지 말라고 적는다(WCAG 3.3.2). 거부된 뒤에 알리면 그 사진은 이미 올라간 뒤다.
 *
 * <p><b>조건은 서버가 정한다.</b> 형식·크기·장수는 서버가 답하고 여기는 그 슬러그를 문구로 바꾼다 —
 * 화면이 먼저 막으면 두 벌이 되고 한쪽만 고치는 날이 온다.
 */
export function MyReviewPhotos({ reviewId, photos }: { reviewId: number; photos: MyReviewPhoto[] }) {
  const router = useRouter();
  const picker = useRef<HTMLInputElement>(null);
  const [pending, startTransition] = useTransition();
  const [failure, setFailure] = useState<string | null>(null);

  const run = (request: () => Promise<unknown>) => {
    setFailure(null);
    startTransition(async () => {
      try {
        await request();
        router.refresh();
      } catch (error) {
        setFailure(messageFor(error));
      } finally {
        if (picker.current) {
          picker.current.value = "";
        }
      }
    });
  };

  const hintId = `review-photo-hint-${reviewId}`;

  return (
    <div className="grid gap-2">
      {photos.length === 0 ? null : (
        <ul className="flex flex-wrap gap-2">
          {photos.map((photo, index) => (
            <li key={photo.reviewImageId} className="grid gap-1">
              {/* eslint-disable-next-line @next/next/no-img-element -- 서명 URL 이라 최적화 서버를 거치면 만료가 꼬인다 */}
              <img
                src={photo.thumbnailUrl}
                alt={`후기 사진 ${index + 1}`}
                className="h-20 w-20 rounded-ui border border-border object-cover"
              />
              <button
                type="button"
                disabled={pending}
                onClick={() => run(() => api(`/api/review-images/${photo.reviewImageId}`, { method: "DELETE" }))}
                className="rounded-ui border border-border px-2 py-0.5 text-xs disabled:opacity-50"
              >
                사진 {index + 1} 떼기
              </button>
            </li>
          ))}
        </ul>
      )}

      <label htmlFor={`review-photo-${reviewId}`} className="text-xs font-medium">
        사진 붙이기
      </label>
      <p id={hintId} className="text-xs text-text-muted">
        JPG 또는 PNG, 한 장에 5MB까지, 후기마다 10장까지 붙일 수 있습니다.
        <br />
        사진은 후기와 함께 누구에게나 보입니다. 얼굴·주소 등 개인정보가 담긴 사진은 올리지 마세요.
      </p>
      <input
        ref={picker}
        id={`review-photo-${reviewId}`}
        name="file"
        type="file"
        accept={ACCEPT}
        disabled={pending}
        aria-describedby={hintId}
        onChange={(event) => {
          const file = event.target.files?.[0];
          if (file) {
            run(() => apiUpload(`/api/reviews/${reviewId}/images`, file));
          }
        }}
        className="text-xs disabled:opacity-60"
      />

      <p aria-live="polite" className="text-xs text-danger-text">
        {failure}
      </p>
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
      return "JPG 또는 PNG 파일만 올릴 수 있습니다. 파일 이름의 확장자도 내용과 같아야 합니다.";
    case "image-limit-reached":
      return "후기마다 사진은 10장까지입니다. 먼저 한 장을 떼 주세요.";
    case "review-not-found":
      return "지워진 후기입니다. 화면을 새로 고쳐 주세요.";
    default:
      return "사진을 바꾸지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
}
