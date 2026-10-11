#!/usr/bin/env python3
"""Template for running a model over benchmark/dataset.jsonl to produce the
outputs file that score.py expects. Not wired to any provider on purpose —
fill in `call_model()` for whichever API/local runtime you're benchmarking
(GenieX/llama.cpp, Ollama, OpenAI-compatible endpoint, Anthropic, ...), then:

  python3 run_model_template.py --model my-model-name --out outputs/my_model.jsonl
  python3 score.py outputs/my_model.jsonl --by-category
"""
import argparse
import json
from pathlib import Path


def call_model(prompt: str, model: str) -> str:
    """Replace this with an actual call. Must return the model's raw text
    reply (same thing SlmFormExtractor.complete() returns before parsing) —
    don't pre-parse here, score.py does that so every model is judged by the
    identical parser the app uses."""
    raise NotImplementedError("wire this up to the model/provider you're benchmarking")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dataset", default=str(Path(__file__).parent / "dataset.jsonl"))
    ap.add_argument("--model", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)

    with open(args.dataset) as fh, out_path.open("w") as out:
        for line in fh:
            item = json.loads(line)
            raw = call_model(item["prompt"], args.model)
            out.write(json.dumps({"id": item["id"], "raw_output": raw}) + "\n")
            print(f"{item['id']}: {raw[:80]!r}")


if __name__ == "__main__":
    main()
