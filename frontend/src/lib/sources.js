// "### File: [project-id] path/to/file.py:12-40" or "... chunk 3", as RagService.formatChunk writes it.
const SOURCE_HEADER = /^### File: (?:\[([^\]]+)\] )?(.+?)(?::(\d+)-(\d+)| chunk \d+)?$/;

/**
 * Display rows for the Sources tab. `sources[i]` is a formatted chunk (header
 * line + code); `citations[i]`, when present, carries the file path, line
 * range and a link to the file on its host. Older responses without citations
 * fall back to parsing the header line.
 */
export function toSourceRows(sources = [], citations = []) {
  return sources.map((source, i) => {
    const text = String(source ?? "");
    const newline = text.indexOf("\n");
    const header = newline >= 0 ? text.slice(0, newline) : text;
    const code = newline >= 0 ? text.slice(newline + 1) : "";
    const match = header.match(SOURCE_HEADER);
    const citation = citations?.[i] || {};

    const filePath = citation.file_path || match?.[2] || header;
    const start = toLine(citation.start_line ?? match?.[3]);
    const end = toLine(citation.end_line ?? match?.[4]) ?? start;
    return {
      index: i + 1,
      filePath,
      lines: start ? (end && end !== start ? `${start}–${end}` : `${start}`) : "",
      url: safeHttpUrl(citation.url),
      code,
    };
  });
}

function toLine(value) {
  const n = Number(value);
  return Number.isInteger(n) && n > 0 ? n : null;
}

/** Only http(s) links are rendered, so a bad value can never become a javascript: URL. */
export function safeHttpUrl(value) {
  if (typeof value !== "string") return null;
  try {
    const url = new URL(value);
    return url.protocol === "https:" || url.protocol === "http:" ? url.href : null;
  } catch {
    return null;
  }
}
