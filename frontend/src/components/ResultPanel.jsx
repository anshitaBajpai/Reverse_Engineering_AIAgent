import { useMemo, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { prepareDocument } from "../lib/markdown.js";
import { exportDocumentPdf } from "../lib/pdf.js";
import SourceList from "./SourceList.jsx";

const MARKDOWN_COMPONENTS = {
  table: ({ children }) => (
    <div className="markdown-table-wrap">
      <table className="markdown-table">{children}</table>
    </div>
  ),
};

const CHAIN_STEPS = [
  { name: "architecture_extraction", label: "Architecture and components" },
  { name: "behavior_extraction", label: "Runtime behavior and data flow" },
  { name: "risk_extraction", label: "Configuration, security and risks" },
];

/**
 * Shows an answer or document as it streams in, with its sources. While a
 * document's analysis steps run (before any text), it shows their progress.
 */
export default function ResultPanel({ result, onStop, onNotice }) {
  const [tab, setTab] = useState("main");
  const isDocument = result.kind === "document";
  const text = useMemo(
    () => (isDocument ? prepareDocument(result.text, result.title) : result.text),
    [isDocument, result.text, result.title],
  );
  const waiting = result.streaming && !result.text;

  async function copy() {
    try {
      await navigator.clipboard.writeText(result.text);
      onNotice("success", "Copied to clipboard.");
    } catch {
      onNotice("error", "Could not copy the result. Select the text and copy it manually.");
    }
  }

  return (
    <section className="result-panel" aria-live="polite" aria-busy={result.streaming}>
      <div className="result-head">
        <span className="step-label">
          {isDocument ? "TECHNICAL DOCUMENT" : "ANSWER"}
          {result.streaming && <span className="stream-status">Writing…</span>}
          {result.stopped && <span className="stream-status stopped">Stopped</span>}
        </span>
        <div className="result-actions">
          {result.streaming && (
            <button className="text-button" onClick={onStop}>
              Stop
            </button>
          )}
          {isDocument && !result.streaming && result.text && (
            <button
              className="text-button"
              onClick={() => exportDocumentPdf(text, result.title || "Technical Document")}
            >
              Download PDF
            </button>
          )}
          {result.text && !result.streaming && (
            <button className="text-button" onClick={copy}>
              Copy
            </button>
          )}
        </div>
      </div>
      <div className="result-tabs" role="tablist" aria-label="Result views">
        <button
          type="button"
          className={`tab-button ${tab === "main" ? "active" : ""}`}
          onClick={() => setTab("main")}
        >
          {isDocument ? "Document" : "Answer"}
        </button>
        <button
          type="button"
          className={`tab-button ${tab === "sources" ? "active" : ""}`}
          onClick={() => setTab("sources")}
          disabled={!result.sources.length}
        >
          Sources{result.sources.length ? ` (${result.sources.length})` : ""}
        </button>
      </div>
      {tab === "sources" ? (
        <SourceList sources={result.sources} citations={result.citations} />
      ) : waiting ? (
        <StreamProgress result={result} />
      ) : (
        <div className={`markdown ${result.streaming ? "streaming" : ""}`}>
          <ReactMarkdown remarkPlugins={[remarkGfm]} components={MARKDOWN_COMPONENTS}>
            {text}
          </ReactMarkdown>
        </div>
      )}
    </section>
  );
}

function StreamProgress({ result }) {
  const foundSources = result.sources.length > 0;
  if (result.kind !== "document") {
    return (
      <p className="progress-copy stream-waiting">
        {foundSources
          ? `Found ${result.sources.length} relevant code sections. Writing the answer…`
          : "Finding relevant code…"}
      </p>
    );
  }
  const analysed = CHAIN_STEPS.filter((step) => result.steps.includes(step.name)).length;
  return (
    <ol className="chain-progress">
      <li className={foundSources ? "done" : "active"}>
        {foundSources ? `Retrieved ${result.sources.length} code sections` : "Retrieving code"}
      </li>
      {CHAIN_STEPS.map((step) => (
        <li
          key={step.name}
          className={result.steps.includes(step.name) ? "done" : foundSources ? "active" : ""}
        >
          {step.label}
        </li>
      ))}
      <li className={analysed === CHAIN_STEPS.length ? "active" : ""}>Writing the document</li>
    </ol>
  );
}
