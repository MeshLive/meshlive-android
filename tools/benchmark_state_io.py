"""Stream large exact-case state files and shared JSON documents without loading them whole.

An exact-case state for the full inventory has hundreds of thousands of rows. The reader maps the file and
decodes one top-level member or one array element at a time, so memory stays proportional to a single row
(plus whatever the caller keeps). It reads both schemas:

- ``benchmark-case-state-v1``: every row embeds a full copy of each change that affects it
  (``row["changes"]``: change definition plus ``needs_retest``).
- ``benchmark-case-state-v2``: the state stores each change definition once (``changes``), a small table of
  distinct per-row outcomes (``change_sets``: ``{"changes": [ids], "needs_retest": [ids]}``) and each row only
  carries ``change_set``, an index into that table.

``row_changes`` re-expands either form to the v1 row shape, so both forms mean exactly the same thing.
"""
import contextlib
import hashlib
import json
import mmap
from pathlib import Path
import re

STATE_V1 = "benchmark-case-state-v1"
STATE_V2 = "benchmark-case-state-v2"
STATE_SCHEMAS = (STATE_V1, STATE_V2)

_TOKEN = re.compile(rb'(?P<s>"[^"\\]*(?:\\.[^"\\]*)*")|(?P<o>[\[{])|(?P<c>[\]}])')
_STRING = re.compile(rb'"[^"\\]*(?:\\.[^"\\]*)*"')
_SCALAR = re.compile(rb"[^\s,\]}]+")
_SPACE = re.compile(rb"\s*")


def _skip(buf, pos):
    return _SPACE.match(buf, pos).end()


def _value_end(buf, pos):
    """End offset of the JSON value that starts at ``pos`` (no leading whitespace)."""
    first = buf[pos]
    if first == 0x22:  # a string
        match = _STRING.match(buf, pos)
        if match is None:
            raise ValueError("Unterminated JSON string")
        return match.end()
    if first in (0x7B, 0x5B):  # an object or array
        depth = 0
        for match in _TOKEN.finditer(buf, pos):
            kind = match.lastgroup
            if kind == "o":
                depth += 1
            elif kind == "c":
                depth -= 1
                if depth == 0:
                    return match.end()
        raise ValueError("Unterminated JSON container")
    match = _SCALAR.match(buf, pos)
    if match is None:
        raise ValueError("Malformed JSON value")
    return match.end()


def _members(buf):
    """Yield (key, value_start, value_end) for each member of the top-level object."""
    pos = _skip(buf, 0)
    if pos >= len(buf) or buf[pos] != 0x7B:
        raise ValueError("Expected a JSON object")
    pos = _skip(buf, pos + 1)
    if buf[pos] == 0x7D:
        return
    while True:
        match = _STRING.match(buf, pos)
        if match is None:
            raise ValueError("Expected an object key")
        key = json.loads(match.group())
        pos = _skip(buf, match.end())
        if buf[pos] != 0x3A:
            raise ValueError("Expected ':' after an object key")
        start = _skip(buf, pos + 1)
        end = _value_end(buf, start)
        yield key, start, end
        pos = _skip(buf, end)
        if buf[pos] == 0x2C:
            pos = _skip(buf, pos + 1)
            continue
        if buf[pos] == 0x7D:
            return
        raise ValueError("Expected ',' or '}' in a JSON object")


def _elements(buf, start, end):
    """Yield (start, end) of each element of the array value at [start, end)."""
    if buf[start] != 0x5B:
        raise ValueError("Expected a JSON array")
    pos = _skip(buf, start + 1)
    if buf[pos] == 0x5D:
        return
    while True:
        stop = _value_end(buf, pos)
        yield pos, stop
        pos = _skip(buf, stop)
        if buf[pos] == 0x2C:
            pos = _skip(buf, pos + 1)
            continue
        if buf[pos] == 0x5D:
            return
        raise ValueError("Expected ',' or ']' in a JSON array")


@contextlib.contextmanager
def _mapped(path):
    path = Path(path)
    with path.open("rb") as handle:
        if path.stat().st_size == 0:
            raise ValueError(f"Empty JSON file: {path}")
        buf = mmap.mmap(handle.fileno(), 0, access=mmap.ACCESS_READ)
        try:
            yield buf
        finally:
            buf.close()


def _decode(buf, start, end):
    return json.loads(bytes(buf[start:end]).decode("utf-8"))


def read_members(path, keys=None, skip=()):
    """Decode selected top-level members (all but ``skip`` when ``keys`` is None); other values are only skipped."""
    wanted = None if keys is None else set(keys)
    result = {}
    with _mapped(path) as buf:
        for key, start, end in _members(buf):
            if (wanted is None or key in wanted) and key not in skip:
                result[key] = _decode(buf, start, end)
    return result


def iter_array(path, key):
    """Yield each element of the top-level array ``key``, one decoded element at a time."""
    with _mapped(path) as buf:
        for name, start, end in _members(buf):
            if name == key:
                for first, last in _elements(buf, start, end):
                    yield _decode(buf, first, last)
                return
    raise ValueError(f"{path} has no top-level array {key!r}")


def file_sha256(path, chunk=1 << 20):
    digest = hashlib.sha256()
    with Path(path).open("rb") as handle:
        while True:
            block = handle.read(chunk)
            if not block:
                return digest.hexdigest()
            digest.update(block)


class ChangeTable:
    """Re-expands a state row's changes to the v1 shape (definition + ``needs_retest``)."""

    def __init__(self, header):
        self.definitions = {item["id"]: item for item in header.get("changes", [])}
        self.sets = header.get("change_sets")

    def expand(self, row):
        if "changes" in row:  # v1 row, or an already expanded row
            return row["changes"]
        if "change_set" not in row:
            return []
        entry = self.sets[row["change_set"]]
        needs = set(entry.get("needs_retest", []))
        return [{**self.definitions[identity], "needs_retest": identity in needs} for identity in entry["changes"]]


def row_changes(header, row):
    """One-off expansion; use ChangeTable when expanding many rows of the same state."""
    return ChangeTable(header).expand(row)


def state_header(path):
    """Every top-level member of a state file except its (large) case list."""
    header = read_members(path, skip=("cases",))
    if header.get("schema") not in STATE_SCHEMAS:
        raise ValueError("Unsupported case-state schema")
    return header


def iter_state_rows(path):
    return iter_array(path, "cases")


def write_state(path, header, rows, trailer=None):
    """Write a v2 state file row by row.

    ``header`` is written first, then every row (one compact JSON line each), then ``trailer()`` members
    computed after the rows (the summary and the change-set table cannot be known earlier). Returns the
    trailer dict.
    """
    def encode(value):
        return json.dumps(value, allow_nan=False, separators=(",", ":"))

    path = Path(path)
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        handle.write("{")
        for key, value in header.items():
            handle.write(f"{encode(key)}:{encode(value)},\n")
        handle.write('"cases":[\n')
        first = True
        for row in rows:
            handle.write(("" if first else ",\n") + encode(row))
            first = False
        handle.write("\n]")
        tail = trailer() if trailer is not None else {}
        for key, value in tail.items():
            handle.write(f",\n{encode(key)}:{encode(value)}")
        handle.write("}\n")
    return tail


def migrate_v1_to_v2(source, target):
    """Convert a v1 state to v2 losslessly, streaming. Returns the number of rows converted.

    The change table is the v1 state's own ``changes`` list; every embedded per-row change must equal its table
    definition (otherwise nothing is guessed and the migration refuses).
    """
    header = read_members(source, skip=("cases",))
    if header.get("schema") != STATE_V1:
        raise ValueError("Only a benchmark-case-state-v1 file can be migrated")
    definitions = {item["id"]: item for item in header.get("changes", [])}
    sets, index = [], {}
    counts = {"rows": 0}

    def converted():
        for row in iter_state_rows(source):
            embedded = row.pop("changes", [])
            for item in embedded:
                clean = {key: value for key, value in item.items() if key != "needs_retest"}
                if definitions.get(item["id"]) != clean:
                    raise ValueError(f"Embedded change differs from the state's change table: {item['id']}")
            key = (tuple(item["id"] for item in embedded),
                   tuple(item["id"] for item in embedded if item.get("needs_retest")))
            if key not in index:
                index[key] = len(sets)
                sets.append({"changes": list(key[0]), "needs_retest": list(key[1])})
            counts["rows"] += 1
            yield {**row, "change_set": index[key]}

    new_header = {"schema": STATE_V2, **{key: value for key, value in header.items()
                                          if key not in ("schema", "summary", "scope")}}
    write_state(target, new_header, converted(),
                lambda: {"change_sets": sets, "summary": header["summary"], "scope": header["scope"]})
    return counts["rows"]


def verify_migration(source, target):
    """Re-expand every migrated row and compare it with the original v1 row. Raises on any difference."""
    old, new = state_header(source), state_header(target)
    if new.get("schema") != STATE_V2:
        raise ValueError("Target is not a v2 state")
    for key in set(old) | set(new):
        if key in ("schema", "change_sets"):
            continue
        if old.get(key) != new.get(key):
            raise ValueError(f"Migrated state differs in {key}")
    table = ChangeTable(new)
    rows = 0
    for before, after in zip(iter_state_rows(source), iter_state_rows(target), strict=True):
        expanded = {key: value for key, value in after.items() if key != "change_set"}
        changes = table.expand(after)
        if changes or "changes" in before:
            expanded["changes"] = changes
        if expanded != before:
            raise ValueError(f"Migrated row differs: {before.get('case_id')}")
        rows += 1
    return rows


def main(argv=None):
    import argparse
    parser = argparse.ArgumentParser(description="Convert a benchmark-case-state-v1 file to v2 and verify it row by row")
    parser.add_argument("action", choices=("migrate", "verify"))
    parser.add_argument("--source", type=Path, required=True, help="Existing v1 state.json (never modified)")
    parser.add_argument("--target", type=Path, required=True, help="v2 state file (migrate: must not exist)")
    args = parser.parse_args(argv)
    try:
        if args.action == "migrate":
            if args.target.exists():
                raise ValueError("Migration target already exists; choose a new file")
            migrate_v1_to_v2(args.source, args.target)
        rows = verify_migration(args.source, args.target)
    except (ValueError, OSError) as error:
        parser.exit(2, f"State migration blocked: {error}\n")
    print(json.dumps({"action": args.action, "rows_verified": rows, "target": str(args.target),
                      "source_sha256": file_sha256(args.source), "target_sha256": file_sha256(args.target)}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
