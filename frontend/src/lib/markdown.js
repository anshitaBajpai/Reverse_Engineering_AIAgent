/**
 * Markdown helpers for generated documents: light block parsing for the PDF
 * export, and normalization of the LLM's section headings and Document
 * Control table before rendering.
 */

export function parseMarkdownBlocks(markdown) {
  const lines = String(markdown).split(/\r?\n/);
  const blocks = [];
  let i = 0;

  while (i < lines.length) {
    const line = lines[i];

    if (!line.trim()) {
      i += 1;
      continue;
    }

    if (line.startsWith("```")) {
      const lang = line.slice(3).trim().toLowerCase();
      const codeLines = [];
      i += 1;
      while (i < lines.length && !lines[i].startsWith("```")) {
        codeLines.push(lines[i]);
        i += 1;
      }
      blocks.push({ type: "code", lang, text: codeLines.join("\n") });
      i += 1;
      continue;
    }

    const headingMatch = line.match(/^(#{1,3})\s+(.*)$/);
    if (headingMatch) {
      blocks.push({
        type: "heading",
        level: headingMatch[1].length,
        text: headingMatch[2].trim(),
      });
      i += 1;
      continue;
    }

    if (line.includes("|")) {
      const tableRows = [];
      let hasSeparator = false;
      while (i < lines.length && lines[i].includes("|")) {
        const currentLine = lines[i].trim();
        if (!currentLine) break;
        if (/^\|?[\s:-]+\|[\s|:-]*$/.test(currentLine)) {
          hasSeparator = true;
        } else {
          const cells = currentLine
            .replace(/^\|/, "")
            .replace(/\|$/, "")
            .split("|")
            .map((cell) => cell.trim());
          tableRows.push(cells);
        }
        i += 1;
      }
      if (hasSeparator && tableRows.length >= 2) {
        blocks.push({ type: "table", rows: tableRows });
        continue;
      }
    }

    const listMatch = line.match(/^[-*+]\s+(.*)$/);
    if (listMatch) {
      const items = [];
      while (i < lines.length) {
        const itemMatch = lines[i].match(/^[-*+]\s+(.*)$/);
        if (!itemMatch) break;
        items.push(itemMatch[1].trim());
        i += 1;
      }
      blocks.push({ type: "list", items });
      continue;
    }

    const paragraphLines = [line.trim()];
    i += 1;
    while (i < lines.length && lines[i].trim()) {
      if (
        lines[i].startsWith("```") ||
        lines[i].match(/^(#{1,3})\s+(.*)$/) ||
        lines[i].match(/^[-*+]\s+(.*)$/)
      ) {
        break;
      }
      paragraphLines.push(lines[i].trim());
      i += 1;
    }
    blocks.push({ type: "paragraph", text: paragraphLines.join(" ") });
  }

  return blocks;
}

export function stripMarkdownMarkers(text) {
  return String(text)
    .replace(/\*\*(.+?)\*\*/g, "$1")
    .replace(/__(.+?)__/g, "$1")
    .replace(/(?<!\w)\*(?!\s)([^*\n]+?)(?<!\s)\*(?!\w)/g, "$1")
    .replace(/_(.+?)_/g, "$1")
    .replace(/`(.+?)`/g, "$1")
    .replace(/^\s*>\s?/gm, "")
    .trim();
}

export function normalizeDocumentControl(markdown, projectName = "this codebase") {
  const text = String(markdown || "");
  if (!text.includes("## Document Control")) {
    return text;
  }

  const replacement = (rows = []) => {
    const defaultRows = [
      `Provide a comprehensive overview of ${projectName} for maintenance and future development.`,
      "Repository structure, source files, and README documentation.",
      "High",
    ];
    const values = defaultRows.map((fallback, index) =>
      rows[index] && rows[index].trim() ? rows[index].trim() : fallback,
    );

    return [
      "## Document Control",
      "",
      "| Document Purpose | Source Basis | Confidence Level |",
      "| --- | --- | --- |",
      `| ${values[0]} | ${values[1]} | ${values[2]} |`,
      "",
    ].join("\n");
  };

  const sectionMatch = text.match(
    /## Document Control([\s\S]*?)(?=\n## |\n# |\s*$)/,
  );
  if (!sectionMatch) return text;

  const sectionBody = sectionMatch[1];
  const tableRows = sectionBody
    .split(/\r?\n/)
    .filter((line) => line.includes("|"))
    .map((line) =>
      line
        .replace(/^\|/, "")
        .replace(/\|$/, "")
        .split("|")
        .map((cell) => cell.trim()),
    )
    .filter((row) => row.length > 1 && !row.every((cell) => /^-+$/.test(cell)));

  const valueLines =
    tableRows.length >= 2
      ? tableRows[1]
      : sectionBody
          .split(/\r?\n/)
          .map((line) => line.trim())
          .filter(Boolean)
          .filter((line) => !line.startsWith("#"))
          .slice(0, 3);

  return text.replace(
    /## Document Control[\s\S]*?(?=\n## |\n# |\s*$)/,
    replacement(valueLines),
  );
}

const DOCUMENT_SECTIONS = [
  "Executive Summary",
  "Scope And Methodology",
  "High-Level System Context",
  "Technology Stack",
  "Repository And Module Structure",
  "Component Inventory",
  "Runtime Behavior And Control Flow",
  "Data Flow And State Management",
  "API Surface And Interfaces",
  "Configuration, Environment, And Deployment",
  "Dependencies And External Integrations",
  "Security And Privacy Review",
  "Operational Risks And Failure Modes",
  "Maintainability Assessment",
  "Unknowns And Assumptions",
  "Recommended Next Steps",
  "Evidence Index",
];

export function normalizeDocumentSections(markdown) {
  const text = String(markdown || "");

  let normalized = text.replace(
    /^(#{2,3})\s+(.+?)\s*$/gm,
    (match, hashes, title) => `${hashes} ${title.trim()}`,
  );

  DOCUMENT_SECTIONS.forEach((section) => {
    const pattern = new RegExp(
      `(^##\\s+${section.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}\\s*$)`,
      "m",
    );
    normalized = normalized.replace(pattern, "\n$1\n");
  });

  return normalized.replace(/\n{3,}/g, "\n\n");
}

/** The document as it should be rendered and exported. */
export function prepareDocument(markdown, projectName) {
  return normalizeDocumentControl(normalizeDocumentSections(markdown), projectName);
}
