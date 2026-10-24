import { describe, expect, it } from "vitest";
import { groupIntoSegments, normalizeWords } from "../src/segmenter.js";

describe("normalizeWords", () => {
  it("sorts, fixes overlaps, enforces min length, drops blanks", () => {
    const { words, warnings } = normalizeWords([
      { text: "b", startMs: 400, endMs: 900 }, { text: "a", startMs: 0, endMs: 500 },
      { text: " ", startMs: 10, endMs: 20 }, { text: "c", startMs: 900, endMs: 900 }
    ]);
    expect(words.map((w) => [w.text, w.startMs, w.endMs])).toEqual([["a", 0, 500], ["b", 500, 900], ["c", 900, 940]]);
    expect(warnings.length).toBeGreaterThan(0);
  });
  it("clamps to and drops beyond duration; result satisfies client invariants", () => {
    const { words } = normalizeWords([{ text: "a", startMs: 0, endMs: 3000 }, { text: "z", startMs: 5000, endMs: 6000 }], 2000);
    expect(words).toEqual([{ text: "a", startMs: 0, endMs: 2000 }]);
    for (const w of words) { expect(w.startMs).toBeGreaterThanOrEqual(0); expect(w.endMs).toBeGreaterThan(w.startMs); }
  });
  it("warns on low confidence", () => {
    const { warnings } = normalizeWords([{ text: "a", startMs: 0, endMs: 100, confidence: 0.2 }]);
    expect(warnings.join()).toMatch(/low-confidence/);
  });
});

describe("groupIntoSegments", () => {
  const w = (text: string, s: number, e: number) => ({ text, startMs: s, endMs: e });
  it("splits on long gaps and sentence punctuation", () => {
    const segs = groupIntoSegments([w("a", 0, 100), w("b", 100, 200), w("c", 2000, 2100), w("d.", 2100, 2200), w("e", 2200, 2300)]);
    expect(segs.map((s) => s.original)).toEqual(["a b", "c d.", "e"]);
    expect(segs.map((s) => s.id)).toEqual(["segment-1", "segment-2", "segment-3"]);
  });
  it("caps words per line", () => {
    const words = Array.from({ length: 25 }, (_, i) => w(`w${i}`, i * 100, i * 100 + 90));
    expect(groupIntoSegments(words).map((s) => s.words.length)).toEqual([12, 12, 1]);
  });
});
