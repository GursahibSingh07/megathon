#!/usr/bin/env python3
"""Builds benchmark/dataset.jsonl from forms.py + dataset.py.

Each line is one benchmark item:
  {
    "id": "<form>__<category>__<n>",
    "form": "<form key>",
    "category": "<probed behavior>",
    "fields": [...],        # full ScreenField list the model is shown (post field_overrides)
    "prompt": "...",        # exact text to send to the model under test
    "transcript": "...",
    "expected_actions": [...]   # ground truth, same shape the model must reply with
  }

Run: python3 generate.py
"""
import copy
import json
from pathlib import Path

from build_prompt import build_prompt
from dataset import EXAMPLES
from forms import FORMS

OUT_PATH = Path(__file__).parent / "dataset.jsonl"


def apply_overrides(fields, overrides):
    fields = copy.deepcopy(fields)
    by_id = {f["id"]: f for f in fields}
    for fid, patch in (overrides or {}).items():
        by_id[fid].update(patch)
    return fields


def main():
    counters = {}
    rows = []
    for ex in EXAMPLES:
        form_key = ex["form"]
        category = ex["category"]
        fields = apply_overrides(FORMS[form_key], ex.get("field_overrides"))
        transcript = ex["transcript"]
        key = (form_key, category)
        counters[key] = counters.get(key, 0) + 1
        item_id = f"{form_key}__{category}__{counters[key]}"
        rows.append({
            "id": item_id,
            "form": form_key,
            "category": category,
            "fields": fields,
            "prompt": build_prompt(transcript, fields),
            "transcript": transcript,
            "expected_actions": ex["expected"],
        })

    with OUT_PATH.open("w") as fh:
        for row in rows:
            fh.write(json.dumps(row) + "\n")

    by_category = {}
    for r in rows:
        by_category[r["category"]] = by_category.get(r["category"], 0) + 1
    print(f"Wrote {len(rows)} examples to {OUT_PATH}")
    for cat, n in sorted(by_category.items()):
        print(f"  {cat}: {n}")


if __name__ == "__main__":
    main()
