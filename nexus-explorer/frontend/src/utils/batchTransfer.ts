export interface BatchTransferItem {
  address: string;
  amount: string;
}

/** Parse canonical BATCH_TRANSFER payload without routing amounts through Number. */
export function parseBatchTransferItems(data: string | null): BatchTransferItem[] {
  if (!data || data.length % 2 !== 0 || !/^[0-9a-fA-F]+$/.test(data)) {
    return [];
  }
  try {
    const bytes = new Uint8Array(data.length / 2);
    for (let i = 0; i < bytes.length; i += 1) {
      bytes[i] = Number.parseInt(data.slice(i * 2, i * 2 + 2), 16);
    }
    const text = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
    // Quote integer amounts before JSON.parse, so monetary values never become JS Numbers.
    const normalized = text.replace(/"amount"\s*:\s*(\d+)/g, '"amount":"$1"');
    const parsed: unknown = JSON.parse(normalized);
    const rawItems = Array.isArray(parsed) ? parsed : (parsed as { items?: unknown } | null)?.items;
    if (!Array.isArray(rawItems)) {
      return [];
    }
    return rawItems.flatMap((item) => {
      if (!item || typeof item !== "object") {
        return [];
      }
      const candidate = item as { address?: unknown; amount?: unknown };
      if (typeof candidate.address !== "string" || candidate.address.length === 0) {
        return [];
      }
      if (typeof candidate.amount !== "string") {
        return [];
      }
      const amount = candidate.amount;
      return /^\d+$/.test(amount) ? [{ address: candidate.address, amount }] : [];
    });
  } catch {
    return [];
  }
}
