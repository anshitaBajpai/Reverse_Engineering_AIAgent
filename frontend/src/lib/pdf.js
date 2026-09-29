import { parseMarkdownBlocks, stripMarkdownMarkers } from "./markdown.js";
import { escapeFilename } from "./format.js";

const BRAND = [124, 140, 255];
const BRAND2 = [90, 215, 255];
const INK = [17, 24, 39];
const MUTED = [107, 114, 128];

/**
 * Renders already-normalized document Markdown to an A4 PDF and downloads it.
 * jsPDF is loaded on demand so it stays out of the main bundle.
 */
export async function exportDocumentPdf(markdown, displayTitle) {
  const { jsPDF } = await import("jspdf");
  const title = escapeFilename(displayTitle);
  const doc = new jsPDF({ unit: "pt", format: "a4" });
  const pageWidth = doc.internal.pageSize.getWidth();
  const pageHeight = doc.internal.pageSize.getHeight();
  const margin = 40;
  const maxWidth = pageWidth - margin * 2;
  const lineHeight = 14;
  const paragraphGap = 8;
  let cursorY = margin;

  const drawPageChrome = () => {
    doc.setFillColor(...BRAND2);
    doc.rect(0, 0, pageWidth, 4, "F");
    doc.setFont("helvetica", "normal");
    doc.setFontSize(8);
    doc.setTextColor(...MUTED);
    doc.text(displayTitle, margin, 22);
  };
  drawPageChrome();

  const newPage = () => {
    doc.addPage();
    drawPageChrome();
    cursorY = margin;
  };

  const ensureSpace = (needed = lineHeight) => {
    if (cursorY + needed > pageHeight - margin) newPage();
  };

  const writeWrapped = (text, fontSize = 11, options = {}) => {
    doc.setFont("helvetica", options.bold ? "bold" : "normal");
    doc.setFontSize(fontSize);
    doc.setTextColor(...(options.color || INK));
    const lines = doc.splitTextToSize(String(text), maxWidth);
    ensureSpace(lines.length * lineHeight);
    lines.forEach((line) => {
      if (cursorY > pageHeight - margin) newPage();
      doc.text(line, options.indent || margin, cursorY);
      cursorY += lineHeight;
    });
    cursorY += options.afterGap || 0;
  };

  const writeTable = (rows) => {
    const colCount = Math.max(...rows.map((row) => row.length));
    const firstColWidth = Math.min(160, maxWidth * 0.34);
    const remainingWidth = maxWidth - firstColWidth;
    const otherColWidth =
      colCount > 1 ? remainingWidth / Math.max(1, colCount - 1) : remainingWidth;
    const columnWidths = Array.from({ length: colCount }, (_, index) =>
      index === 0 ? firstColWidth : otherColWidth,
    );
    const padX = 7;
    const padY = 6;

    rows.forEach((row, rowIndex) => {
      const cellLines = row.map((cell, cellIndex) =>
        doc.splitTextToSize(
          stripMarkdownMarkers(cell || ""),
          columnWidths[cellIndex] - padX * 2,
        ),
      );
      const rowHeight =
        Math.max(...cellLines.map((lines) => lines.length)) * lineHeight + padY * 2;
      ensureSpace(rowHeight + 4);

      if (rowIndex === 0) {
        doc.setFillColor(...BRAND);
      } else if (rowIndex % 2 === 0) {
        doc.setFillColor(236, 239, 255);
      } else {
        doc.setFillColor(255, 255, 255);
      }
      doc.rect(margin, cursorY - 11, maxWidth, rowHeight, "F");
      doc.setDrawColor(210, 214, 219);
      doc.rect(margin, cursorY - 11, maxWidth, rowHeight);

      let cellX = margin;
      const textColor = rowIndex === 0 ? [255, 255, 255] : INK;
      row.forEach((cell, cellIndex) => {
        if (cellIndex > 0) {
          doc.line(cellX, cursorY - 11, cellX, cursorY - 11 + rowHeight);
        }
        doc.setFont("helvetica", rowIndex === 0 ? "bold" : "normal");
        doc.setFontSize(10);
        doc.setTextColor(...textColor);
        cellLines[cellIndex].forEach((line, lineIndex) => {
          doc.text(line, cellX + padX, cursorY + padY + lineIndex * lineHeight - 1);
        });
        cellX += columnWidths[cellIndex];
      });

      cursorY += rowHeight;
    });
    cursorY += paragraphGap;
  };

  parseMarkdownBlocks(markdown).forEach((block) => {
    if (block.type === "heading") {
      const sizeByLevel = { 1: 16, 2: 13, 3: 12 };
      const afterGap = block.level === 1 ? 9 : 5;
      writeWrapped(stripMarkdownMarkers(block.text), sizeByLevel[block.level] || 12, {
        bold: true,
        color: BRAND,
        afterGap,
      });
      if (block.level === 1) {
        doc.setDrawColor(...BRAND2);
        doc.setLineWidth(1.2);
        doc.line(margin, cursorY - afterGap + 3, pageWidth - margin, cursorY - afterGap + 3);
      }
    } else if (block.type === "paragraph") {
      writeWrapped(stripMarkdownMarkers(block.text), 11, { afterGap: paragraphGap });
    } else if (block.type === "list") {
      block.items.forEach((item) => {
        writeWrapped(`• ${stripMarkdownMarkers(item)}`, 11, {
          indent: margin + 12,
          afterGap: 2,
        });
      });
      cursorY += paragraphGap;
    } else if (block.type === "table") {
      writeTable(block.rows);
    } else if (block.type === "code") {
      ensureSpace(28);
      doc.setFont("courier", "normal");
      doc.setFontSize(10);
      doc.setTextColor(...INK);
      block.text.split(/\r?\n/).forEach((codeLine) => {
        doc.splitTextToSize(codeLine || " ", maxWidth - 12).forEach((segment) => {
          ensureSpace(lineHeight);
          doc.text(segment, margin + 6, cursorY);
          cursorY += lineHeight;
        });
      });
      cursorY += paragraphGap;
    }
  });

  const totalPages = doc.internal.getNumberOfPages();
  for (let page = 1; page <= totalPages; page += 1) {
    doc.setPage(page);
    doc.setDrawColor(...BRAND2);
    doc.setLineWidth(0.75);
    doc.line(margin, pageHeight - 30, pageWidth - margin, pageHeight - 30);
    doc.setFont("helvetica", "normal");
    doc.setFontSize(9);
    doc.setTextColor(...MUTED);
    doc.text(displayTitle, margin, pageHeight - 18);
    doc.text(`Page ${page} of ${totalPages}`, pageWidth - margin, pageHeight - 18, {
      align: "right",
    });
  }

  doc.save(`${title}.pdf`);
}
