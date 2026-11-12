"""Generate the readable benchmark index from the existing ledger; never rescore or use phones."""
import argparse
import collections
import datetime as dt
import hashlib
import html
import json
import os
from pathlib import Path
from urllib.parse import quote

from benchmark_layout import ROOT, LEDGER, resolve
from benchmark_state_io import file_sha256, iter_state_rows, read_members, state_header


def render(ledger_path, output, root=ROOT, ledger=None, state_path=None):
    """Validate all indexed evidence and prepare the view without changing files.

    ``state_path`` is a prepared state that is not yet at the ledger's state pointer (a checkpoint candidate);
    its bytes must hash to the candidate pointer. State and inventory are streamed, never loaded whole.
    """
    root, output = Path(root).resolve(), Path(output).resolve()
    if output.name != "index.html":
        raise ValueError("The derived view must be named index.html; original reports are not overwritten")
    ledger_path = resolve(ledger_path, root=root)
    if ledger is None:
        ledger = json.loads(ledger_path.read_text(encoding="utf-8"))

    def verified(pointer):
        path = resolve(pointer["path"], root=root)
        if hashlib.sha256(path.read_bytes()).hexdigest() != pointer["sha256"]:
            raise ValueError(f"Indexed evidence changed: {path}")
        return path, json.loads(path.read_text(encoding="utf-8"))

    def verified_large(pointer, source=None):
        path = resolve(pointer["path"], root=root)
        if file_sha256(source or path) != pointer["sha256"]:
            raise ValueError(f"Indexed evidence changed: {path}")
        return path

    def link(path, label):
        path = resolve(path, root=root)
        if not path.is_file():
            raise ValueError(f"Indexed evidence is missing: {path}")
        target = quote(os.path.relpath(path, output.parent).replace(os.sep, "/"), safe="/")
        return f'<a href="{html.escape(target, quote=True)}">{html.escape(str(label))}</a>'

    current = ledger["current_inventory"]
    pointers = {key: verified_large(current[key], state_path if key == "state" else None)
                for key in ("inventory", "bundle", "input_requirements", "state")}
    state_file = state_path or pointers["state"]
    state = state_header(state_file)
    counts, total = collections.Counter(), 0
    for row in iter_state_rows(state_file):
        counts[row["state"]] += 1
        total += 1
    counts = dict(counts)
    if counts != state["summary"] or counts != current["states"] or total != current["case_count"]:
        raise ValueError("Ledger and exact case state disagree; refresh the ledger first")
    if state.get("candidate_apk_sha256") != current["candidate_apk_sha256"]:
        raise ValueError("Ledger and exact state candidate APK disagree; refresh the ledger first")
    reports = []
    for pointer in ledger.get("unified_reports", []):
        path, report = verified(pointer)
        if report.get("schema") != "benchmark-suite-v1":
            raise ValueError("Unsupported suite-result schema")
        actual = dict(collections.Counter(row["status"] for row in report["results"]))
        if actual != report["state_counts"] or actual != pointer["state_counts"]:
            raise ValueError("Indexed suite result counts disagree")
        rows = []
        for row in report["results"]:
            families = "; ".join(f"{name}: {value['status']}" for name, value in row["families"].items())
            rows.append("<tr>" + "".join(f"<td>{html.escape(str(value))}</td>" for value in
                (row["case_id"], row["status"], row.get("origin", "UNMEASURED"), families)) + "</tr>")
        reports.append(f"<p>{link(path.with_name('report.html'), 'Detailed scored report')} · "
            f"{link(path, 'Authoritative JSON')} · {html.escape(report['generated_at'])}</p>"
            "<table><thead><tr><th>Request</th><th>Result</th><th>Evidence type</th><th>Measurement families</th></tr>"
            f"</thead><tbody>{''.join(rows)}</tbody></table>")
    historical = []
    for row in ledger.get("executions", []):
        evidence = row["evidence"]
        cells = [html.escape(str(row["id"])), html.escape(str(row["state"])),
                 link(root / evidence["manifest"], "Original run"), link(root / evidence["score"], "Original score")]
        historical.append("<tr>" + "".join(f"<td>{cell}</td>" for cell in cells) + "</tr>")
    media_notice = ""
    if ledger.get("media_retirement"):
        path, audit = verified(ledger["media_retirement"])
        media_notice = (f"<p>{audit['privacy_cleanup']['removed_files']:,} benchmark media files were retired for privacy or obsolete diagnostics. "
            "Recorded scores and logs remain; pixel metrics that depended on those files cannot be recomputed. "
            + "".join(f"Later, {section['retired_files']:,} raw media/log files of {section['runs']:,} closed runs no record cites "
                      "were retired; their results and scores remain and the protected backup keeps the files. "
                      for key, section in sorted(audit.items()) if key.startswith("evidence_retirement_")) +
            f"{link(path, 'Media retirement evidence')}</p>")
    status = " · ".join(f"{html.escape(name)}: {count:,}" for name, count in sorted(counts.items()))
    data_links = " · ".join(link(path, key.replace("_", " ").title()) for key, path in pointers.items())
    remaining = "".join(f"<li>{html.escape(reason)}</li>" for reason in read_members(pointers["inventory"], keys=("remaining",)).get("remaining", []))
    page = f"""<!doctype html><html lang="en"><meta charset="utf-8">
<meta name="benchmark-view" content="benchmark-index-v1"><title>Bluetooth benchmark results</title>
<style>body{{font:16px system-ui;max-width:1200px;margin:32px auto;padding:0 20px;color:#222}}
table{{width:100%;border-collapse:collapse;margin:12px 0 28px}}td,th{{border:1px solid #ccc;padding:9px;text-align:left;overflow-wrap:anywhere}}
th{{background:#f0f3f6}}a{{color:#075fa5}}input{{padding:8px;width:90%;margin:12px 0}}.status{{font-size:22px;font-weight:600}}code{{overflow-wrap:anywhere}}</style>
<h1>Bluetooth benchmark results</h1>
<p>Generated {dt.datetime.now(dt.timezone.utc).isoformat()}. This view reads the existing ledger and scores; it does not execute or score a test.</p>
<h2>Current required coverage</h2><p class="status">{status}</p>
<p>Required phones: {html.escape(', '.join(current['required_phones']))}. Live availability is verified separately before a run.</p>
<p>Candidate APK: <code>{html.escape(current['candidate_apk_sha256'])}</code></p><p>{data_links} · {link(ledger_path, 'Machine ledger')}</p>
<ul>{remaining}</ul>
<h2>Scored executions</h2><p>These are recorded results with their own requests and builds. Only an exact verified match can update current coverage.</p>
{''.join(reports) or '<p>No unified scored executions are indexed.</p>'}
<h2>What the files mean</h2>
<table><tr><th>File</th><th>Purpose</th></tr>
<tr><td>suite-result.json / analysis.json</td><td>Measured scores, limits, validity and reasons for that execution.</td></tr>
<tr><td>state.json</td><td>Exact remaining cases, last measured result and whether a change requires retesting.</td></tr>
<tr><td>manifest.json</td><td>Which phones, build, settings, input and lifecycle actions actually ran.</td></tr>
<tr><td>CSV / logs / TXT</td><td>Individual samples and raw diagnostic evidence used to calculate or explain results.</td></tr>
<tr><td>XML / images</td><td>Screen observations. They need a declared measurement purpose; they are not benchmark scores.</td></tr>
<tr><td>YUV / IVF / MP4 / audio</td><td>Reference inputs or actual captured media; their identities and roles are declared in each run.</td></tr></table>
<h2>Earlier recorded tests ({len(historical)})</h2>
<p>Original tests and scoring versions remain traceable here. Their recorded outcomes do not replace the current coverage above.</p>
{media_notice}
<details><summary>Browse original evidence</summary><label>Filter <input id="filter" placeholder="Phone, request or recorded outcome"></label>
<table id="history"><thead><tr><th>Original run</th><th>Recorded outcome</th><th>Request and traces</th><th>Recorded score</th></tr></thead>
<tbody>{''.join(historical)}</tbody></table></details>
<script>document.getElementById('filter').addEventListener('input',event=>{{const query=event.target.value.toLowerCase();
for(const row of document.querySelectorAll('#history tbody tr'))row.hidden=!row.textContent.toLowerCase().includes(query);}});</script></html>"""
    if output.exists() and 'content="benchmark-index-v1"' not in output.read_text(encoding="utf-8"):
        raise ValueError("Refusing to replace an unrelated HTML file")
    return page, {"cases": total, "states": counts, "historical_executions": len(historical), "unified_reports": len(reports)}


def generate(ledger_path, output, root=ROOT):
    page, result = render(ledger_path, output, root)
    output = Path(output).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_name(output.name + ".tmp")
    temporary.write_text(page, encoding="utf-8")
    temporary.replace(output)
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ledger", type=Path, default=LEDGER)
    parser.add_argument("--output", type=Path, default=ROOT / "benchmarks/index.html")
    args = parser.parse_args(argv)
    try:
        result = generate(args.ledger, args.output)
    except (ValueError, KeyError, OSError) as error:
        parser.exit(2, f"Benchmark index not refreshed: {error}\n")
    print(json.dumps(result))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
