import { describe, expect, it } from "vitest";
import { parseBatchTransferItems } from "../../utils/batchTransfer";

function hex(text: string): string {
  return Array.from(new TextEncoder().encode(text), (byte) =>
    byte.toString(16).padStart(2, "0"),
  ).join("");
}

describe("parseBatchTransferItems", () => {
  it("preserves integer amounts beyond JavaScript's safe-number range", () => {
    const data = hex(
      '{"total_count":1,"total_amount":"9007199254740993","items":[{"address":"NEXlarge","amount":9007199254740993}]}',
    );

    expect(parseBatchTransferItems(data)).toEqual([
      { address: "NEXlarge", amount: "9007199254740993" },
    ]);
  });

  it("accepts the legacy array shape and rejects malformed payloads", () => {
    expect(parseBatchTransferItems(hex('[{"address":"NEXone","amount":7}]'))).toEqual([
      { address: "NEXone", amount: "7" },
    ]);
    expect(parseBatchTransferItems("not-hex")).toEqual([]);
    expect(parseBatchTransferItems(hex("not-json"))).toEqual([]);
    expect(parseBatchTransferItems(hex('{"items":[{"address":"NEXbad","amount":-1}]}'))).toEqual(
      [],
    );
    expect(parseBatchTransferItems(null)).toEqual([]);
  });
});
