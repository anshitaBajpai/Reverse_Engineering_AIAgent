import { toSourceRows } from "../lib/sources.js";

/**
 * The chunks an answer was grounded in. Each file links to its exact lines on
 * GitHub (pinned to the indexed commit) when the host supports it; the code
 * itself is one click away.
 */
export default function SourceList({ sources = [], citations = [] }) {
  const rows = toSourceRows(sources, citations);
  if (!rows.length) return null;
  return (
    <div className="sources">
      <strong>Grounding sources</strong>
      <ol className="source-list">
        {rows.map((row) => (
          <li className="source-item" key={`${row.index}-${row.filePath}-${row.lines}`}>
            <div className="source-item-head">
              <span className="source-index">{row.index}</span>
              {row.url ? (
                <a
                  className="source-link"
                  href={row.url}
                  target="_blank"
                  rel="noopener noreferrer"
                  title="Open this code in a new tab"
                >
                  <span className="source-path">{row.filePath}</span>
                  {row.lines && <span className="source-lines">:{row.lines}</span>}
                  <span className="source-external" aria-hidden="true">↗</span>
                </a>
              ) : (
                <span className="source-plain">
                  <span className="source-path">{row.filePath}</span>
                  {row.lines && <span className="source-lines">:{row.lines}</span>}
                </span>
              )}
            </div>
            {row.code && (
              <details className="source-code">
                <summary>Show code</summary>
                <pre>
                  <code>{row.code}</code>
                </pre>
              </details>
            )}
          </li>
        ))}
      </ol>
    </div>
  );
}
