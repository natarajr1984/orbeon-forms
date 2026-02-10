#!/usr/bin/env python3
"""Heuristic PR code review agent.

Scans changed lines between a base and head revision and emits actionable comments
focused on coding standards, time complexity, performance, and NFR concerns.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


@dataclass
class Finding:
    file: str
    line: int
    category: str
    severity: str
    message: str
    suggestion: str
    code: str


def run_git(args: list[str]) -> str:
    result = subprocess.run(["git", *args], capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or f"git {' '.join(args)} failed")
    return result.stdout


def choose_base_ref(user_base: str | None) -> str:
    if user_base:
        return user_base
    candidates = ["origin/main", "origin/master", "main", "master"]
    for candidate in candidates:
        ok = subprocess.run(["git", "rev-parse", "--verify", candidate], capture_output=True, text=True)
        if ok.returncode == 0:
            return candidate
    return "HEAD~1"


def parse_added_lines(diff_text: str) -> dict[str, list[tuple[int, str]]]:
    file_lines: dict[str, list[tuple[int, str]]] = defaultdict(list)
    current_file = None
    new_line = 0

    for raw_line in diff_text.splitlines():
        if raw_line.startswith("+++ b/"):
            current_file = raw_line[6:]
            continue
        if raw_line.startswith("@@"):
            match = re.search(r"\+(\d+)(?:,(\d+))?", raw_line)
            if match:
                new_line = int(match.group(1))
            continue
        if current_file is None:
            continue
        if raw_line.startswith("+") and not raw_line.startswith("+++"):
            file_lines[current_file].append((new_line, raw_line[1:]))
            new_line += 1
        elif raw_line.startswith("-") and not raw_line.startswith("---"):
            continue
        else:
            new_line += 1

    return file_lines


def analyze_line(file: str, line_no: int, code: str, context: list[str]) -> Iterable[Finding]:
    stripped = code.strip()

    if code.rstrip() != code:
        yield Finding(file, line_no, "Coding standards", "low", "Trailing whitespace found.", "Remove trailing spaces.", code)

    if "\t" in code:
        yield Finding(file, line_no, "Coding standards", "low", "Tab character found in changed line.", "Use spaces for indentation unless the file convention requires tabs.", code)

    if len(code) > 120:
        yield Finding(file, line_no, "Coding standards", "low", "Line exceeds 120 characters.", "Wrap or refactor for readability.", code)

    if re.search(r"\b(TODO|FIXME|XXX)\b", code):
        yield Finding(file, line_no, "NFR", "medium", "Unresolved marker added in committed code.", "Either resolve it now or reference an issue with owner/date.", code)

    if re.search(r"\b(eval|exec)\s*\(", code):
        yield Finding(file, line_no, "NFR", "high", "Dynamic code execution can introduce security and reliability issues.", "Use explicit parsing/dispatch instead of runtime evaluation.", code)

    if re.search(r"(password|secret|token|api[_-]?key)\s*[:=]\s*['\"][^'\"]+['\"]", code, re.IGNORECASE):
        yield Finding(file, line_no, "NFR", "high", "Potential hard-coded credential detected.", "Move secrets to environment variables or secure secret storage.", code)

    if re.search(r"for\s*\(.*\)\s*\{?", stripped) or re.search(r"\bfor\b.+\bin\b", stripped):
        if any(re.search(r"for\s*\(|\bfor\b.+\bin\b", c.strip()) for c in context[-3:]):
            yield Finding(file, line_no, "Time complexity", "medium", "Potential nested loop added.", "Verify data sizes; consider indexing/caching to avoid O(n^2+) behavior.", code)

    if re.search(r"\+\s*=\s*.*\+", code) and any("for" in c for c in context[-3:]):
        yield Finding(file, line_no, "Performance", "medium", "String concatenation inside loop can be costly.", "Use a builder/buffer/join strategy.", code)

    if re.search(r"\.contains\(.*\)", code) and any(re.search(r"for\s*\(|\bfor\b.+\bin\b", c) for c in context[-3:]):
        yield Finding(file, line_no, "Performance", "medium", "Membership checks inside loops may become hot.", "Consider a hash-based set/map for O(1) lookups.", code)


def analyze_changes(added_lines: dict[str, list[tuple[int, str]]]) -> list[Finding]:
    findings: list[Finding] = []
    for file, lines in added_lines.items():
        context: list[str] = []
        for line_no, code in lines:
            findings.extend(analyze_line(file, line_no, code, context))
            context.append(code)
    return findings


def render_report(findings: list[Finding], limit: int) -> str:
    if not findings:
        return "# PR Code Review Report\n\nNo issues detected by heuristic checks in changed lines."

    findings = findings[:limit]
    grouped: dict[str, list[Finding]] = defaultdict(list)
    for f in findings:
        grouped[f.category].append(f)

    out = ["# PR Code Review Report", "", "Automated heuristic feedback for committed changes and PR diff."]
    for category in ["Coding standards", "Time complexity", "Performance", "NFR"]:
        items = grouped.get(category, [])
        out.append(f"\n## {category} ({len(items)})")
        if not items:
            out.append("- No findings.")
            continue
        for i, f in enumerate(items, start=1):
            out.append(f"- **{i}. [{f.severity.upper()}] `{f.file}:{f.line}`** — {f.message}")
            out.append(f"  - Code: `{f.code.strip()}`")
            out.append(f"  - Suggestion: {f.suggestion}")
    return "\n".join(out)


def main() -> int:
    parser = argparse.ArgumentParser(description="PR review agent for standards, complexity, performance, and NFR checks.")
    parser.add_argument("--base", help="Base revision/ref (default: auto-detected)")
    parser.add_argument("--head", default="HEAD", help="Head revision/ref (default: HEAD)")
    parser.add_argument("--max-comments", type=int, default=100, help="Maximum findings to output")
    parser.add_argument("--output", help="Write markdown report to this file")
    args = parser.parse_args()

    base_ref = choose_base_ref(args.base)
    merge_base = run_git(["merge-base", base_ref, args.head]).strip()
    diff = run_git(["diff", "--unified=0", f"{merge_base}..{args.head}"])

    added = parse_added_lines(diff)
    findings = analyze_changes(added)
    report = render_report(findings, args.max_comments)

    if args.output:
        Path(args.output).write_text(report + "\n", encoding="utf-8")
    else:
        print(report)

    print(f"\nReviewed {sum(len(v) for v in added.values())} added lines across {len(added)} file(s).", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
