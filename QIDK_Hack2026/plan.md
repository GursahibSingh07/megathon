# Plan: the on-device SLM fills a form from speech (a few hours)

Scope, as decided: **speech in, English only, forms on this device, a few hours of working time**,
with Claude writing most of the code, so the plan favours the shortest path that gives a measured,
safe result over completeness.

```
mic -> VAD -> Whisper (NPU, :5051) -> transcript
                                          |
screen scan -> field list ----------------+--> SLM (Llama 3.2 3B, NPU, :5052)
                                                  |  "Full name | Priya Shah" lines
                          parser + validator + code-side normalisers
                                                  |
                          FormAction.Type / Click -> accessibility executor -> read-back
                                                  |
                          still-empty required field -> spoken question -> fill that one field
```

## What already works

- Speech to text on the NPU (0.46-1.05 s per utterance), the screen scan, typing/clicking, read-back.
- `FormExtractor.plan(transcript, screen)`: the SLM only has to be a second implementation;
  `FormPipeline`, the executor and the keyboard panel stay as they are.
- `RuleBasedExtractor` stays as the fallback.

## What the spike on the board showed (measured today, same narration each time)

Llama 3.2 3B via `genie-t2t-run`, QAIRT 2.37.1, running while `whisper_npud` was resident on the NPU.

| Finding | Evidence |
|---|---|
| Both models fit on the NPU together | Llama ran with Whisper loaded; Whisper kept answering |
| One call takes about 4.1-4.4 s, including loading the three model binaries | 4.01 s, 4.10 s, 4.42 s; a first attempt with a longer JSON answer took 8.0 s |
| Asking for **JSON is unreliable** | Output had `"value:"` with a missing quote, the whole sentence as the name, an invented empty phone, severity "High" missed |
| A **line format keyed by field name** works much better | `Full name \| Priya Shah`, `Severity \| High`, correct email, correct "what happened" |
| Keying by numeric id is worse | The model copied the example's id (`1 \| Priya Shah` for a field whose id was 3) |
| It invents names when none were said | Narration without a name returned `Full name \| John Doe` |
| It garbles spoken digits | "four one five five five zero one one nine" came back as 4155055109 |
| Choice fields can be wrong | A forklift hitting a shelf was filed as "Security" |
| Launching Genie again right after another Genie process exited **segfaulted** (3 times); after a pause it worked | Not diagnosed. Likely the NPU session not yet released; a persistent service avoids it |

What this means for the design:

1. **Output format: one `field name | value` line per field.** No JSON. Key by label, map back to
   ids in code.
2. **The model does only the semantic mapping.** Names, emails, phone numbers and other numbers
   are normalised by deterministic code from the transcript (the "at"/"dot" email logic already
   exists in `RuleBasedExtractor`; add spoken-digit parsing).
3. **Validator with a grounding check:** a free-text value is kept only if its words appear in
   the narration. This removes "John Doe". Option values must match a listed option.
4. **A persistent service**, not a process per request: removes the 2-3 s model load and the
   back-to-back crash.

## Work plan (about 5 hours, in this order)

### Hour 1 - extractor, parser, validator (no device needed)
- `SlmPrompt`: builds the prompt from `ScreenSnapshot` (only empty fields, label plus type,
  options listed in the label), one worked example with different field names than the real form,
  Llama 3 chat format, "skip fields the user did not mention".
- `SlmOutputParser`: reads `name | value` lines; ignores empty values, unknown names, duplicates,
  extra chatter; case-insensitive label match with fuzzy fallback.
- `SlmValidator`: option matching (normalised, unambiguous or dropped), grounding check for text,
  email/phone/number normalisers fed from the transcript rather than from the model, no
  overwriting a non-empty field.
- `SlmExtractor : FormExtractor`, calling a `SlmClient` interface (so it can be tested with a fake).
- JVM unit tests using the real outputs above as fixtures: the good run, the "John Doe" run, the
  garbled phone, an output with extra text, and an empty output.

### Hour 2 - `llm_npud`, the persistent service
- C++ daemon on `127.0.0.1:5052` using the Genie C API already on the board
  (`qairt-2.37.1/include/Genie/GenieDialog.h`, `libGenie.so`): create the dialog once from
  `genie_config.json`, `GenieDialog_query` per request, reset between requests, a mutex, one
  thread per client (the Whisper service lesson).
- Same wire format as `whisper_npud` (length-prefixed). Build with the Genie headers kept out of
  git, like the QNN headers. `tools/llm/deploy.sh` + `start_llm.sh` mirroring `tools/npu/`.
- Time-box to 1.5 h. If the Genie C API fights back, fall back to a loopback relay that runs
  `genie-t2t-run` per request and serialises requests with a pause (works, about 4 s per fill).
- `SlmClient` in the app: persistent socket, probe, down-cache (copy of `NpuClient`).

### Hour 3 - wire it in and test with real speech
- `FormPipeline`: SLM first, rules when the service is down, the output is empty, or nothing
  survives validation.
- Test on the board with TTS-generated narrations on the HTML incident form and on the Google
  Form: mention fields in order, out of order, conversationally, with one field missing.
- Show progress in the panel ("Understanding...", "Filling..."), since the SLM adds about 2-4 s.

### Hour 4 - missing fields and a spoken question
- After a fill, for each required field still empty: a template question ("What is your
  <label>?"), spoken with Android `TextToSpeech`, mic paused while it speaks, then the answer is
  run through the same path in **single-field mode** (prompt contains only that field).
- Required = the form's own marker (a `*` in the label); otherwise report empties only.
- Read back anything that came from digits ("I heard 4 1 5 5 5 5 0 1 1 9, correct?").
- **Never submit automatically.** Submission needs an explicit spoken "submit".

### Hour 5 - small evaluation and notes
- 15-20 narrations over the two forms, text ground truth, run through rules vs SLM+validator:
  per-field correct / wrong / invented / missed, and time per stage. Enough for an honest table,
  not a full study.
- Update the report with what was measured. Commit in logical pieces.

## Cut line if time runs short
Stop after Hour 3 with the evaluation from Hour 5 on the fills. Hour 4 is the first thing to drop
(keep only the "still empty" list in the panel).

## Risks

| Risk | Handling |
|---|---|
| Genie C API takes longer than the time box | Fallback relay around `genie-t2t-run` (see Hour 2) |
| Latency about 2-4 s per fill | Only empty fields in the prompt, short example, progress text; rules remain a fast path for narrations that name every field |
| Model errors on choices ("Security" for a forklift) | Accepted limit for a 3B model; list options clearly, report low-confidence fills in the read-back, measure the rate |
| Invented values | Grounding check; measured in the evaluation |
| Intermittent Genie startup crash | Persistent service; if it also happens there, retry once and fall back to rules |
| Any app can call the loopback services | Acceptable on the dev board; stated in the report. The old `server.py` (all interfaces, shared key) is not used |
| Services stop on reboot | Start script for now; boot start only if time remains |
| Licensing | Llama 3.2 licence applies to the model; Qualcomm SDK files stay out of git |

## Definition of done
- Speaking a free-form English narration fills the matching fields on both test forms, on the
  board, with no network.
- A value the speaker did not say is never typed (checked by test, not by eye).
- The keyboard still works with the SLM service stopped.
- The evaluation table and the measured latencies are in the report.
