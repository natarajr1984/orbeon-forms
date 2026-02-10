# PR Review Agent

`tools/pr_review_agent.py` is a lightweight code-review agent for pull requests.
It analyzes changed lines in the commit/PR diff and outputs comments grouped by:

- Coding standards
- Time complexity
- Performance
- NFR (security/reliability/maintainability)

## Usage

```bash
python3 tools/pr_review_agent.py
```

Optional arguments:

- `--base <ref>`: base branch/ref (auto-detected by default)
- `--head <ref>`: head ref (default `HEAD`)
- `--max-comments <n>`: cap findings (default `100`)
- `--output <file>`: write markdown report to a file

Example:

```bash
python3 tools/pr_review_agent.py --base origin/master --head HEAD --output review-report.md
```

## How it decides what to review

1. Resolves a base ref (`origin/main`, `origin/master`, `main`, `master`, then `HEAD~1`).
2. Uses `git merge-base` to compute the effective comparison point.
3. Scans only **added lines** in the PR diff.
4. Emits actionable comments with file + line references.

## Notes

- The checks are heuristic by design; results should be validated by a human reviewer.
- This tool is intentionally dependency-free and uses only Python standard library + git.
