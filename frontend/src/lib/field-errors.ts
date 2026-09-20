import { ApiError, camelCase } from "./api";

/**
 * 서버가 지목한 칸을 폼의 칸에 붙인다(`Q129`).
 *
 * <p><b>이름 표기가 둘이다.</b> 서버는 요청에 쓴 이름으로 말하고(snake_case, 중첩이면
 * {@code shipping.postal_code} 처럼 점 표기) 폼의 칸 이름은 camelCase 다.
 *
 * <p><b>이름을 사다리로 찾는다</b>(`Q136`). 긴 것부터 재고 폼이 아는 첫 이름에 붙인다.
 *
 * <pre>
 * skus[0].price_incl_vat  →  skus.0.priceInclVat   ← 줄까지 가리키는 폼
 *                         →  skus.priceInclVat     ← 목록이지만 줄은 안 가리는 폼
 *                         →  priceInclVat          ← 중첩을 한 단계로 편 폼
 * </pre>
 *
 * <p><b>마지막 마디만 쓰던 것을 넓힌 것이다.</b> 주문서의 배송지처럼 중첩을 한 단계로 편 폼은
 * 맨 아래 칸이 맞는데, <b>목록이 끼면 그 방식이 줄을 지운다</b> — `skus[0]` 과 `skus[1]` 의
 * 같은 칸이 한 이름으로 뭉개져서 어느 줄의 값인지 못 가린다. 사다리를 쓰면 **줄을 가리는 폼은
 * 정확히 붙고, 안 가리는 폼은 지금까지와 똑같이 동작한다.**
 *
 * <p><b>아는 칸 목록을 받는다.</b> 이름을 계산만 하고 붙이면 {@code cart_item_ids} 처럼
 * 화면에 칸이 없는 것까지 붙이려 들고, 그 문구는 아무 데도 안 뜨고 사라진다 —
 * `13h` 의 「모르는 칸을 지목하지 않는다」가 막는 자리다.
 *
 * <p><b>못 붙인 것을 버리지 않는다.</b> {@link Placed#rest} 로 돌려주고 폼이 전체 오류 자리에
 * 같이 그린다. 버리면 사용자는 「다시 확인해 주세요」만 보고 무엇을 확인할지 모른다.
 *
 * <p><b>폼마다 다시 안 쓴다.</b> 주문서·가입·상품 등록이 같은 모양을 쓰고, 두 벌이 되면
 * 한쪽만 고치는 날이 온다({@code lib/format} 이 `13e` 에서 같은 판단을 했다).
 */
export type Placed<Field extends string> = {
  byField: Partial<Record<Field, string>>;
  rest: string[];
};

export function placeErrors<Field extends string>(
  error: unknown,
  fields: readonly Field[],
): Placed<Field> {
  if (!(error instanceof ApiError)) {
    return { byField: {}, rest: [] };
  }

  const byField: Partial<Record<Field, string>> = {};
  const rest: string[] = [];

  /** 이미 붙인 칸이 어느 경로에서 왔나. 같은 경로가 두 번 오는 것과 다른 줄이 겹치는 것을 가른다 */
  const placedFrom = new Map<Field, string>();

  for (const { field, message } of error.errors) {
    const known = candidatesOf(field).reduce<Field | undefined>(
      (found, candidate) => found ?? fields.find((one) => one === candidate),
      undefined,
    );

    if (!known) {
      rest.push(message);
      continue;
    }

    const taken = placedFrom.get(known);

    if (taken === undefined) {
      byField[known] = message;
      placedFrom.set(known, field);
      continue;
    }

    if (taken === field) {
      // 같은 칸에 여럿이 오면 첫 것만 쓴다. 두 줄을 겹쳐 보여 주면 칸 높이가 들쭉날쭉해지고,
      // 하나를 고치면 보통 나머지도 같이 풀린다.
      continue;
    }

    // **다른 경로가 같은 칸으로 뭉갰다**(`Q136`). `skus[0]` 과 `skus[1]` 의 같은 칸을
    // 한 칸에 그리는 폼이 그 모양이다 — 덮어쓰면 뒤 줄의 사유가 **아무 데도 안 남는다.**
    rest.push(message);
  }

  return { byField, rest };
}

/**
 * 서버가 부른 이름에서 폼이 알 수도 있는 이름들을 <b>긴 것부터</b> 만든다(`Q136`).
 *
 * <p>목록 색인은 {@code skus[0]} 과 {@code skus.0} 둘 다 온다 — 서버가 무엇을 쓰든
 * 같은 사다리가 나오게 먼저 점으로 편다.
 */
function candidatesOf(field: string): string[] {
  const parts = field.replace(/\[(\d+)\]/g, ".$1").split(".").filter(Boolean);
  const named = parts.map(camelCase);
  const withoutIndex = named.filter((part) => !/^\d+$/.test(part));

  return [
    named.join("."),
    withoutIndex.join("."),
    withoutIndex[withoutIndex.length - 1] ?? "",
  ].filter(Boolean);
}

/** 첫 번째로 틀린 칸. 목록 순서가 곧 화면 순서라 <b>위에 있는 칸부터</b> 나온다 */
export function firstBadField<Field extends string>(
  placed: Placed<Field>,
  fields: readonly Field[],
): Field | undefined {
  return fields.find((field) => placed.byField[field] !== undefined);
}
