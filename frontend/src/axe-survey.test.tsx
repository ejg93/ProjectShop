import { render } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { axe } from "vitest-axe";

vi.mock("@/lib/api", async (o) => ({ ...(await o<typeof import("@/lib/api")>()), api: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: vi.fn(), push: vi.fn() }) }));

import { LoginForm } from "@/app/login/login-form";
import { CartLine, type CartItem } from "@/app/cart/cart-line";
import { PurchasePanel } from "@/app/products/[productId]/purchase-panel";
import { Pager } from "@/components/pager";

const ITEM: CartItem = {
  cartItemId: 1, skuId: 100, productId: 7, productName: "티셔츠", optionLabel: "검정 / L",
  sellerId: 3, sellerName: "가게", priceInclVat: 19000, shippingFee: 3000, quantity: 2, available: true,
};

describe("axe 신호 재기", () => {
  const cases: [string, () => HTMLElement][] = [
    ["LoginForm", () => render(<LoginForm />).container],
    ["CartLine", () => render(<CartLine item={ITEM} />).container],
    ["CartLine(살 수 없음)", () => render(<CartLine item={{ ...ITEM, available: false }} />).container],
    ["PurchasePanel", () => render(
      <PurchasePanel
        options={[{ productOptionId: 1, name: "색상", values: [{ productOptionValueId: 11, value: "검정" }] }]}
        skus={[{ skuId: 100, priceInclVat: 19000, inStock: true, optionValueIds: [11] }]}
        shippingFee={3000}
      />).container],
  ];

  for (const [name, mount] of cases) {
    it(`${name}`, async () => {
      const result = await axe(mount());
      const violations = (result as { violations: { id: string; impact: string; nodes: unknown[] }[] }).violations;
      const r = result as { violations: unknown[]; passes: unknown[]; incomplete: { id: string }[]; inapplicable: unknown[] };
      console.log(`${name}: 위반 ${r.violations.length} · 통과 ${r.passes.length} · 판단보류 ${r.incomplete.length}(${r.incomplete.map((i) => i.id).join(",")}) · 해당없음 ${r.inapplicable.length}`);
      expect(true).toBe(true);
    });
  }
});
