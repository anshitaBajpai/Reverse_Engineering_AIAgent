import test from "node:test";
import assert from "node:assert/strict";
import { safeHttpUrl, toSourceRows } from "./sources.js";

const SHA = "0123456789abcdef0123456789abcdef01234567";

test("toSourceRows uses citations for path, lines and link", () => {
  const [row] = toSourceRows(
    ["### File: [u1-o-r] src/app.py:10-20\nScope: class App:\n    def run(self): ..."],
    [{
      project_id: "u1-o-r",
      file_path: "src/app.py",
      start_line: 10,
      end_line: 20,
      url: `https://github.com/o/r/blob/${SHA}/src/app.py#L10-L20`,
    }],
  );

  assert.deepEqual(row, {
    index: 1,
    filePath: "src/app.py",
    lines: "10–20",
    url: `https://github.com/o/r/blob/${SHA}/src/app.py#L10-L20`,
    code: "Scope: class App:\n    def run(self): ...",
  });
});

test("toSourceRows falls back to the header when citations are missing", () => {
  const rows = toSourceRows([
    "### File: [p] docs/guide.md:3-3\n# Guide",
    "### File: README.md chunk 2\ntext",
  ]);

  assert.equal(rows[0].filePath, "docs/guide.md");
  assert.equal(rows[0].lines, "3");
  assert.equal(rows[0].url, null);
  assert.equal(rows[1].filePath, "README.md");
  assert.equal(rows[1].lines, "");
});

test("safeHttpUrl only allows http(s) links", () => {
  assert.equal(safeHttpUrl("https://github.com/o/r"), "https://github.com/o/r");
  assert.equal(safeHttpUrl("javascript:alert(1)"), null);
  assert.equal(safeHttpUrl("not a url"), null);
  assert.equal(safeHttpUrl(undefined), null);
});
