#!/usr/bin/env python3
"""TResult-discard audit (docs/tech-debt.md "TResult-returning ops are silently discardable").

Finds statement-position calls of TResult-returning functions whose result is dropped —
the silent-failure class behind Timeline's visit-loss bug. Run from the repo root:

    python3 scripts/tresult-audit.py | grep PROD

Output: PROD|TEST <file>:<line> <function> <source line>. Hand-review required: known
false-positive classes (Unit-returning same-named cousins, stdlib lock/await/execute
collisions, expression-position calls) are listed in the tech-debt entry.
"""
import os, re, sys
ROOT = '.'
decl_re = re.compile(r'\bfun\s+(?:<[^>]+>\s+)?(?:[\w.<>?]+\.)?(\w+)\s*\([^)]*$|\bfun\s+(?:<[^>]+>\s+)?(?:[\w.<>?]+\.)?(\w+)\s*\(.*?\)\s*:\s*TResult<')
names = set()
files = []
for dirpath, dirnames, filenames in os.walk(ROOT):
    dirnames[:] = [d for d in dirnames if d not in ('build', '.git', 'node_modules', '.gradle')]
    for f in filenames:
        if f.endswith('.kt'):
            files.append(os.path.join(dirpath, f))
# Pass 1: declarations (single-line and multi-line signatures ending ": TResult<")
sig_re = re.compile(r'\bfun\s+(?:<[^>]+>\s+)?(?:[\w.<>?]+\.)*(\w+)\s*\(')
for path in files:
    src = open(path, encoding='utf-8', errors='replace').read()
    for m in sig_re.finditer(src):
        # find the matching ) of the param list, then check for ": TResult<"
        i = m.end() - 1
        depth = 0
        for j in range(i, min(len(src), i + 4000)):
            c = src[j]
            if c == '(': depth += 1
            elif c == ')':
                depth -= 1
                if depth == 0:
                    tail = src[j+1:j+40]
                    if re.match(r'\s*:\s*TResult<', tail):
                        names.add(m.group(1))
                    break
# Drop names too generic / known-safe wrappers that RETURN the result themselves
print(f"# {len(names)} TResult-returning function names", file=sys.stderr)

call_res = {n: re.compile(r'(?<![\w.])(?:\w+\??\.)*' + re.escape(n) + r'\s*\(') for n in names}
findings = []
for path in files:
    is_test = '/commonTest/' in path or '/desktopTest/' in path or '/jvmTest/' in path or '/androidTest/' in path or path.endswith('Test.kt')
    src = open(path, encoding='utf-8', errors='replace').read()
    lines = src.split('\n')
    for name, cre in call_res.items():
        for m in cre.finditer(src):
            # skip declarations & imports
            linestart = src.rfind('\n', 0, m.start()) + 1
            line_prefix = src[linestart:m.start()]
            full_line = src[linestart:src.find('\n', m.start())]
            if re.search(r'\bfun\s', line_prefix) or full_line.strip().startswith('import ') or full_line.strip().startswith('*') or full_line.strip().startswith('//'):
                continue
            # statement position: only whitespace before the call expression start on its line?
            expr_start = m.start()
            # walk expression back over receiver chain: find where the chain begins
            k = expr_start
            while k > linestart and (src[k-1].isalnum() or src[k-1] in '._?'):
                k -= 1
            before = src[linestart:k].strip()
            if before != '':
                continue  # bound/returned/argument/etc.
            # previous non-ws char before the line: if '(', ',', '=', arrow etc -> used as arg/value
            p = linestart - 1
            while p > 0 and src[p] in '\n\r\t ':
                p -= 1
            if src[p] in '(,=' or src[p-5:p+1].endswith('return'):
                continue
            # find matching close paren of the call
            j = m.end() - 1
            depth = 0
            end = None
            while j < len(src):
                c = src[j]
                if c == '(':
                    depth += 1
                elif c == ')':
                    depth -= 1
                    if depth == 0:
                        end = j
                        break
                j += 1
            if end is None:
                continue
            after = src[end+1:end+80]
            am = re.match(r'\s*', after)
            rest = after[am.end():]
            if rest.startswith('.') or rest.startswith('?.') or rest.startswith('!!') or rest.startswith('?:') or rest.startswith('as '):
                continue  # chained/consumed
            # lambda-final-expression heuristic: next non-ws char is '}' AND the line before call... too fuzzy; flag it.
            lineno = src.count('\n', 0, m.start()) + 1
            findings.append((path.lstrip('./'), lineno, name, 'TEST' if is_test else 'PROD', full_line.strip()[:110]))

findings.sort()
for f in findings:
    print(f"{f[3]}\t{f[0]}:{f[1]}\t{f[2]}\t{f[4]}")
print(f"# total: {len(findings)} ({sum(1 for f in findings if f[3]=='PROD')} prod, {sum(1 for f in findings if f[3]=='TEST')} test)", file=sys.stderr)
