"use client";

import Link from "next/link";
import { useState } from "react";

import { ApiError, api } from "@/lib/api";

/** 옵션 한 축과 고를 수 있는 값들. 「색상」에 「검정·흰색」 같은 것 */
export type OptionGroup = {
  productOptionId: number;
  name: string;
  values: { productOptionValueId: number; value: string }[];
};

/**
 * 살 수 있는 조합 하나.
 *
 * @param optionValueIds 이 조합이 어느 값들로 이루어졌나. <b>옵션이 없는 상품은 빈 배열</b>(`8c`)
 */
export type PublicSku = {
  skuId: number;
  priceInclVat: number;
  inStock: boolean;
  optionValueIds: number[];
};

/**
 * 옵션을 고르고 담는 자리.
 *
 * <p><b>여기만 클라이언트 컴포넌트다</b>(`D24` 「경계를 잎사귀로 내린다」).
 * 고른 값을 들고 있어야 하고 버튼 누름에 반응해야 해서다. 나머지 상세는 서버가 그린다.
 *
 * <p><b>고른 값을 주소에 안 둔다.</b> 「상태는 주소에 둔다」는 목록의 쪽·필터처럼
 * <b>돌아왔을 때 살아 있어야 하는 것</b>에 걸린다(`D24`). 고르는 중인 값은 그 화면에서만 산다.
 */
export function PurchasePanel({
  options,
  skus,
}: {
  options: OptionGroup[];
  skus: PublicSku[];
}) {
  // 옵션 축 id → 고른 값 id. 축이 없는 상품이면 영원히 빈 객체다.
  const [picked, setPicked] = useState<Record<number, number>>({});
  const [added, setAdded] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const [sending, setSending] = useState(false);

  const allPicked = options.every((option) => picked[option.productOptionId] !== undefined);
  const sku = allPicked ? matchSku(skus, Object.values(picked)) : undefined;

  async function add() {
    if (!sku) {
      return;
    }

    setSending(true);
    setFailure(null);
    try {
      await api("/api/cart/items", {
        method: "POST",
        body: { skuId: sku.skuId, quantity: 1 },
      });
      setAdded(true);
    } catch (error) {
      setFailure(messageFor(error));
    } finally {
      setSending(false);
    }
  }

  return (
    <div className="grid gap-4">
      {options.map((option) => (
        <fieldset key={option.productOptionId} className="grid gap-2">
          {/*
            fieldset 의 이름은 legend 다. 위에 p 로 얹으면 보조기술이 라디오 묶음과 안 잇는다.
          */}
          <legend className="text-sm font-semibold">{option.name}</legend>

          <div className="flex flex-wrap gap-2">
            {option.values.map((value) => (
              <label
                key={value.productOptionValueId}
                className="
                  cursor-pointer rounded-ui border border-border px-3 py-1.5 text-sm
                  transition-colors duration-200
                  hover:border-accent-text
                  has-[:checked]:border-accent has-[:checked]:bg-accent
                  has-[:checked]:font-semibold has-[:checked]:text-accent-on
                  has-[:focus-visible]:outline-2 has-[:focus-visible]:outline-offset-2
                  has-[:focus-visible]:outline-accent-text
                "
              >
                <input
                  type="radio"
                  name={`option-${option.productOptionId}`}
                  value={value.productOptionValueId}
                  checked={picked[option.productOptionId] === value.productOptionValueId}
                  onChange={() => {
                    setPicked((current) => ({
                      ...current,
                      [option.productOptionId]: value.productOptionValueId,
                    }));
                    // 고른 것이 바뀌면 앞선 결과는 지금 조합의 것이 아니다.
                    setAdded(false);
                    setFailure(null);
                  }}
                  // 화면에서 지우되 초점과 낭독에서는 안 지운다. display:none 이면 키보드로 못 고른다.
                  className="sr-only"
                />
                {value.value}
              </label>
            ))}
          </div>
        </fieldset>
      ))}

      <Status options={options} allPicked={allPicked} sku={sku} />

      {sku?.inStock ? (
        <button
          type="button"
          onClick={add}
          disabled={sending}
          className="
            justify-self-start rounded-ui bg-accent px-4 py-2.5 text-sm font-semibold text-accent-on
            transition-[background-color,transform] duration-200
            hover:bg-accent-hover
            motion-safe:active:translate-y-px
            focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
            disabled:opacity-60
          "
        >
          {sending ? "담는 중" : "장바구니에 담기"}
        </button>
      ) : null}

      {/*
        결과가 소리로도 전해져야 한다(`D20` 「동적으로 바뀌는 것」). 빈 채로 미리 두는 이유는
        나중에 만들어 붙이면 보조기술이 그 영역을 안 보고 있어서 읽지 않기 때문이다.
      */}
      <p role="status" className="text-sm text-text-muted">
        {added ? (
          <>
            장바구니에 담았습니다.{" "}
            <Link
              href="/cart"
              className="
                font-semibold text-accent-text underline underline-offset-4
                focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
              "
            >
              장바구니 보기
            </Link>
          </>
        ) : null}
      </p>

      <p role="alert" className="text-sm text-danger-text">
        {failure}
      </p>
    </div>
  );
}

/**
 * 지금 고른 것이 무엇인가.
 *
 * <p>가격을 여기서 말한다. <b>조합마다 값이 달라서</b> 고르기 전에는 하나로 못 적는다 —
 * 목록이 최저가만 보여준 것과 같은 이유다.
 */
function Status({
  options,
  allPicked,
  sku,
}: {
  options: OptionGroup[];
  allPicked: boolean;
  sku: PublicSku | undefined;
}) {
  if (!allPicked) {
    return (
      <p className="text-sm text-text-muted">
        {options.length === 1
          ? `${options[0].name}${objectParticle(options[0].name)} 선택해 주세요.`
          : "옵션을 모두 선택해 주세요."}
      </p>
    );
  }

  if (!sku) {
    // 조합이 다 있으리라는 보장이 없다. 셀러가 일부만 등록할 수 있다.
    return <p className="text-sm text-text-muted">선택하신 조합은 판매하지 않습니다.</p>;
  }

  return (
    <div className="grid gap-1">
      <p className="text-lg font-semibold">{sku.priceInclVat.toLocaleString("ko-KR")}원</p>
      {sku.inStock ? null : <p className="text-sm text-danger-text">품절된 조합입니다.</p>}
    </div>
  );
}

/**
 * 「을」이냐 「를」이냐. <b>옵션 이름을 셀러가 지어서 미리 못 적는다.</b>
 *
 * <p>`을(를)` 로 뭉개지 않는다 — 서식 문서 말투라 화면 문구 규칙과 안 맞는다(`D20`).
 *
 * <p>한글 음절은 코드값이 규칙적이라 받침 여부가 나눗셈으로 나온다.
 * 한글이 아닌 이름(`Size`)이면 조사를 붙일 자리가 아니라 「를」로 둔다 — 그쪽이 덜 어색하다.
 */
function objectParticle(word: string): "을" | "를" {
  const last = word.trim().codePointAt(word.trim().length - 1) ?? 0;
  const isHangulSyllable = last >= 0xac00 && last <= 0xd7a3;

  if (!isHangulSyllable) {
    return "를";
  }
  // 음절 = ((초성 * 21) + 중성) * 28 + 종성. 나머지가 0 이면 받침이 없다.
  return (last - 0xac00) % 28 === 0 ? "를" : "을";
}

/**
 * 고른 값들로 조합을 찾는다.
 *
 * <p><b>순서를 안 믿는다.</b> 서버가 값 id 순으로 내려주지만 고른 순서는 사람이 정한다.
 * 개수가 같고 전부 들어 있으면 같은 조합이다 — 한 축에서 하나만 고르므로 중복이 없다.
 *
 * <p>옵션이 없는 상품은 양쪽이 다 빈 배열이라 첫 조합이 그대로 걸린다(`8c`).
 */
function matchSku(skus: PublicSku[], pickedValueIds: number[]): PublicSku | undefined {
  return skus.find(
    (candidate) =>
      candidate.optionValueIds.length === pickedValueIds.length &&
      pickedValueIds.every((id) => candidate.optionValueIds.includes(id)),
  );
}

/**
 * 실패를 사람이 읽는 말로.
 *
 * <p><b>`type` 으로 갈린다</b>(`D5`·`D20`). 상태 코드로 가르면 서버가 코드를 조정할 때
 * 화면이 같이 틀어지고, `detail` 을 그대로 쓰면 개발자 문체가 사용자에게 나간다.
 */
function messageFor(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return "잠시 후 다시 시도해 주세요.";
  }

  switch (error.type) {
    case "urn:shop:error:out-of-stock":
      return "재고가 모자랍니다. 개수를 줄이거나 다른 조합을 선택해 주세요.";
    case "urn:shop:error:sku-not-buyable":
      return "지금은 구매할 수 없는 조합입니다.";
    default:
      return "장바구니에 담지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
}
