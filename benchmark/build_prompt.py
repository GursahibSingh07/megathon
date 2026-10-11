"""Faithful Python port of SlmFormExtractor.buildPrompt / parseActions
(app/src/main/java/com/example/qidk_hack2026/form/SlmFormExtractor.kt), so
the benchmark prompts and scoring logic match exactly what the on-device
model is actually given and judged against.
"""
import json
import textwrap

INSTRUCTIONS = textwrap.dedent("""\
    You fill in on-screen form fields from spoken narration. Reply with ONLY a JSON array,
    no prose, no markdown fences, no explanation. Use exactly these three action values —
    never any other word:
    {"id": <id>, "action": "type", "value": "<text>"} for an INPUT field,
    {"id": <id>, "action": "click"} for an OPTION the narration selects, or
    {"id": <id>, "action": "select", "value": "<option text>"} for a DROPDOWN field — value
    is the option to pick, in the narration's own words (it doesn't have to match "current"
    exactly; the app opens the dropdown and finds the closest item).
    Omit fields the narration doesn't mention. Never invent a value that wasn't said or
    clearly implied by it. Never emit a click for a Submit, Send, Done, Next, Finish, or
    similar action button — only fill or select fields; the form must never be submitted.
    A field's "current" value is what's already filled in. Leave it alone unless BOTH:
    (a) this narration mentions that field again, AND (b) the new value it implies is
    clearly more correct than "current" (fixing a typo, a misheard word, or outdated info).
    If the narration mentions a field but doesn't give a value that's clearly better than
    what's already there, don't touch it — never overwrite a correct value just because the
    field came up again.

    Example: given fields [{"id":5,"kind":"INPUT","label":"Email"},
    {"id":9,"kind":"OPTION","label":"Yes","group":"Subscribe?"},
    {"id":12,"kind":"DROPDOWN","label":"Country","current":"India"}] and narration
    "email is jane at example dot com, yes subscribe me, country united states", reply exactly:
    [{"id":5,"action":"type","value":"jane@example.com"},{"id":9,"action":"click"},
    {"id":12,"action":"select","value":"United States"}]

    Example: given the same fields and narration "um, okay, let's see" (nothing said matches
    any field), reply exactly: []

    Example: given fields [{"id":5,"kind":"INPUT","label":"Email","current":"jane@xmple.com"}]
    and narration "sorry, my email is jane at example dot com, spelled e-x-a-m-p-l-e", reply
    exactly: [{"id":5,"action":"type","value":"jane@example.com"}] (current was a typo, the
    narration mentions the field again and clearly gives the correct spelling).

    Example: given fields [{"id":5,"kind":"INPUT","label":"Email","current":"jane@example.com"}]
    and narration "yeah my email's fine, let's move on" (the field is mentioned but no new
    value is given, and current already looks correct), reply exactly: []
""")


def visible_fields(fields):
    """Mirrors buildPrompt: an OPTION is only offered if it's a real choice control."""
    return [f for f in fields if not (f["kind"] == "OPTION" and not f["choice"])]


def field_json(f):
    return {
        "id": f["id"], "kind": f["kind"], "label": f["label"],
        "group": f["group"], "current": f["current"], "selected": f["selected"],
    }


def build_prompt(transcript, fields):
    shown = [field_json(f) for f in visible_fields(fields)]
    fields_json = json.dumps(shown)
    narration_json = json.dumps(transcript)
    return (
        INSTRUCTIONS
        + f"\nFields on screen: {fields_json}\n"
        + f"\nNarration: {narration_json}\n"
    )


def parse_actions(raw, fields):
    """Python port of SlmFormExtractor.parseActions: extracts the JSON array,
    drops anything that doesn't resolve to a legal action for its field."""
    start = raw.find("[")
    end = raw.rfind("]")
    if start < 0 or end <= start:
        return []
    try:
        array = json.loads(raw[start:end + 1])
    except json.JSONDecodeError:
        return []

    by_id = {f["id"]: f for f in fields}
    actions = []
    for obj in array:
        if not isinstance(obj, dict):
            continue
        fid = obj.get("id")
        field = by_id.get(fid)
        if field is None:
            continue
        action = obj.get("action")
        if action == "type" and field["kind"] == "INPUT":
            value = str(obj.get("value", "")).strip()
            if value:
                actions.append({"id": fid, "action": "type", "value": value})
        elif action == "click" and field["kind"] == "OPTION" and field["choice"]:
            actions.append({"id": fid, "action": "click"})
        elif action == "select" and field["kind"] == "DROPDOWN":
            value = str(obj.get("value", "")).strip()
            if value:
                actions.append({"id": fid, "action": "select", "value": value})
    return actions
