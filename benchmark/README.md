# Voice-form-fill extraction benchmark

Synthetic dataset for benchmarking different models on the task
`SlmFormExtractor` performs in the app: given a spoken-narration transcript
and the fields currently on screen, decide which fields to fill/select/click.

Ported directly from
`app/src/main/java/com/example/qidk_hack2026/form/SlmFormExtractor.kt`
(buildPrompt / parseActions) so every item uses the exact instructions,
few-shot examples, and output contract the on-device model is actually
judged against in production — not a paraphrase of them.

## Files

- `forms.py` — 8 realistic form field layouts (incident report, job
  application, event registration, customer feedback, medical intake,
  checkout, account signup, bug report), using the same `ScreenField` shape
  the accessibility scanner produces.
- `dataset.py` — 69 hand-authored (form, narration, ground-truth actions)
  examples across 14 categories, each probing one rule from the prompt:
  simple direct dictation, out-of-order mentions, partial narrations, no
  match, spelled-out emails, typo correction, "already correct, don't
  touch", naming an option without its question, an option already
  selected, submit-button traps, explicit skip, informal dropdown wording,
  spoken numbers, and last-value-wins on repeated mentions.
- `build_prompt.py` — Python port of `buildPrompt`/`parseActions`, so prompts
  sent to a candidate model and the parser used to score its reply match the
  Kotlin implementation exactly.
- `generate.py` — assembles `dataset.jsonl` from the above.
- `score.py` — scores a model's raw replies against `dataset.jsonl`.
- `run_model_template.py` — template for producing a model's outputs file;
  fill in `call_model()` for whatever provider/runtime you're benchmarking.

## Usage

```bash
# 1. (Re)build the dataset
python3 generate.py

# 2. Run a model over it (fill in call_model() first)
python3 run_model_template.py --model my-model --out outputs/my_model.jsonl

# 3. Score
python3 score.py outputs/my_model.jsonl --by-category
```

`outputs/*.jsonl` is one `{"id": ..., "raw_output": ...}` per line — the
model's raw text reply, unparsed. `score.py` parses it with the same
`parseActions` logic the app uses, then compares the resulting action set
against ground truth and reports exact-match rate plus precision/recall/F1
over individual field actions, both overall and per category.

## Extending

Add new field layouts to `forms.py` and new `(form, category, transcript,
expected)` entries to `dataset.py`, then rerun `generate.py`. Keep
`expected_actions` hand-verified against the rules in `build_prompt.py`'s
`INSTRUCTIONS` — there's no oracle here besides careful reading of the
prompt the real app sends.
