#!/usr/bin/env python3
"""Scores a model's raw outputs against benchmark/dataset.jsonl.

Input: a JSONL file of {"id": "<item id>", "raw_output": "<model's raw text reply>"}
one line per dataset item (see run_model_template.py for how to produce this
for a given model/provider).

For each item the raw output is parsed with the exact same logic the app
uses (build_prompt.parse_actions), then compared against expected_actions as
an unordered set of (id, action, value) tuples — order doesn't matter since
the app only cares which fields get touched and with what, not sequence.

Usage:
  python3 score.py outputs/my_model.jsonl
  python3 score.py outputs/my_model.jsonl --dataset dataset.jsonl --by-category
"""
import argparse
import json
from collections import defaultdict
from pathlib import Path

from build_prompt import parse_actions


def action_key(a):
    # "value" is absent for click actions; normalize so comparisons are exact-but-fair.
    return (a["id"], a["action"], a.get("value", "").strip().lower())


def score_item(expected, predicted_raw, fields):
    predicted = parse_actions(predicted_raw, fields)
    exp_set = {action_key(a) for a in expected}
    got_set = {action_key(a) for a in predicted}

    tp = len(exp_set & got_set)
    fp = len(got_set - exp_set)
    fn = len(exp_set - got_set)
    exact_match = exp_set == got_set
    return {"tp": tp, "fp": fp, "fn": fn, "exact_match": exact_match}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("outputs", help="JSONL of {id, raw_output} — the model's raw replies")
    ap.add_argument("--dataset", default=str(Path(__file__).parent / "dataset.jsonl"))
    ap.add_argument("--by-category", action="store_true", help="also break metrics down per category")
    args = ap.parse_args()

    dataset = {}
    with open(args.dataset) as fh:
        for line in fh:
            item = json.loads(line)
            dataset[item["id"]] = item

    outputs = {}
    with open(args.outputs) as fh:
        for line in fh:
            row = json.loads(line)
            outputs[row["id"]] = row["raw_output"]

    missing = set(dataset) - set(outputs)
    if missing:
        print(f"WARNING: {len(missing)} dataset items have no output (counted as empty reply): "
              f"{sorted(missing)[:5]}{'...' if len(missing) > 5 else ''}")

    totals = {"tp": 0, "fp": 0, "fn": 0, "exact_match": 0, "n": 0}
    per_cat = defaultdict(lambda: {"tp": 0, "fp": 0, "fn": 0, "exact_match": 0, "n": 0})

    for item_id, item in dataset.items():
        raw = outputs.get(item_id, "")
        result = score_item(item["expected_actions"], raw, item["fields"])
        for k in ("tp", "fp", "fn"):
            totals[k] += result[k]
            per_cat[item["category"]][k] += result[k]
        totals["exact_match"] += int(result["exact_match"])
        totals["n"] += 1
        per_cat[item["category"]]["exact_match"] += int(result["exact_match"])
        per_cat[item["category"]]["n"] += 1

    def report(name, d):
        tp, fp, fn, n = d["tp"], d["fp"], d["fn"], d["n"]
        precision = tp / (tp + fp) if (tp + fp) else 1.0
        recall = tp / (tp + fn) if (tp + fn) else 1.0
        f1 = 2 * precision * recall / (precision + recall) if (precision + recall) else 0.0
        exact = d["exact_match"] / n if n else 0.0
        print(f"{name:28s}  n={n:3d}  exact_match={exact:.1%}  "
              f"precision={precision:.1%}  recall={recall:.1%}  f1={f1:.1%}")

    print("=" * 90)
    report("OVERALL", totals)
    if args.by_category:
        print("-" * 90)
        for cat in sorted(per_cat):
            report(cat, per_cat[cat])
    print("=" * 90)


if __name__ == "__main__":
    main()
