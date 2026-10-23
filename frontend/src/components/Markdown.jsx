import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";

const MARKDOWN_COMPONENTS = {
  table: ({ children }) => (
    <div className="markdown-table-wrap">
      <table className="markdown-table">{children}</table>
    </div>
  ),
};

/** Rendered answer or document text; `streaming` shows a caret after the last block. */
export default function Markdown({ text, streaming = false }) {
  return (
    <div className={`markdown ${streaming ? "streaming" : ""}`}>
      <ReactMarkdown remarkPlugins={[remarkGfm]} components={MARKDOWN_COMPONENTS}>
        {text}
      </ReactMarkdown>
    </div>
  );
}
