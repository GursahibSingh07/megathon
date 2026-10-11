"""Form definitions used by the synthetic benchmark.

Each form is a list of field dicts shaped exactly like the `ScreenField`
scanned by FormAccessibilityService and handed to SlmFormExtractor.buildPrompt
in the app (see app/src/main/java/.../form/FormModels.kt and SlmFormExtractor.kt):

    {id, kind: INPUT|OPTION|DROPDOWN, label, group, current, selected, choice}

A plain button (Submit/Next/Done) is included in a few forms with
kind=OPTION, choice=False — exactly as FormAccessibilityService reports it —
so the dataset can check whether a model can be talked into "clicking" it.
buildPrompt() filters those out before they ever reach the model (the real
app never offers them), so build_prompt.py drops them the same way; they
stay in the form definition purely as a record of what the scanner saw.
"""

def field(id, kind, label, group="", current="", selected=False, choice=False):
    return {
        "id": id, "kind": kind, "label": label, "group": group,
        "current": current, "selected": selected, "choice": choice,
    }


FORMS = {
    "incident_report": [
        field(0, "INPUT", "Location"),
        field(1, "INPUT", "Description"),
        field(2, "OPTION", "Low", group="Severity", choice=True),
        field(3, "OPTION", "Medium", group="Severity", choice=True),
        field(4, "OPTION", "High", group="Severity", choice=True),
        field(5, "INPUT", "Email"),
        field(6, "OPTION", "Submit", choice=False),
    ],
    "job_application": [
        field(0, "INPUT", "Full name"),
        field(1, "INPUT", "Email"),
        field(2, "INPUT", "Phone number"),
        field(3, "DROPDOWN", "Experience level", current="Entry level"),
        field(4, "OPTION", "Remote", group="Work arrangement", choice=True),
        field(5, "OPTION", "Hybrid", group="Work arrangement", choice=True),
        field(6, "OPTION", "Onsite", group="Work arrangement", choice=True),
        field(7, "OPTION", "Submit application", choice=False),
    ],
    "event_registration": [
        field(0, "INPUT", "Full name"),
        field(1, "INPUT", "Email"),
        field(2, "INPUT", "Number of guests", current="1"),
        field(3, "DROPDOWN", "Meal preference", current="No preference"),
        field(4, "OPTION", "Yes", group="Attending?", choice=True),
        field(5, "OPTION", "No", group="Attending?", choice=True),
    ],
    "customer_feedback": [
        field(0, "INPUT", "Comments"),
        field(1, "INPUT", "Email"),
        field(2, "DROPDOWN", "Overall rating", current="Not rated"),
        field(3, "OPTION", "Yes", group="Would you recommend us?", choice=True),
        field(4, "OPTION", "No", group="Would you recommend us?", choice=True),
    ],
    "medical_intake": [
        field(0, "INPUT", "Full name"),
        field(1, "INPUT", "Date of birth"),
        field(2, "INPUT", "Known allergies"),
        field(3, "DROPDOWN", "Blood type", current="Unknown"),
        field(4, "OPTION", "Yes", group="Do you smoke?", choice=True),
        field(5, "OPTION", "No", group="Do you smoke?", choice=True),
    ],
    "checkout_shipping": [
        field(0, "INPUT", "Street address"),
        field(1, "INPUT", "City"),
        field(2, "INPUT", "ZIP code"),
        field(3, "INPUT", "Phone number"),
        field(4, "DROPDOWN", "Country", current="India"),
        field(5, "OPTION", "Standard shipping", group="Shipping method", choice=True),
        field(6, "OPTION", "Express shipping", group="Shipping method", choice=True),
        field(7, "OPTION", "Place order", choice=False),
    ],
    "account_signup": [
        field(0, "INPUT", "Username"),
        field(1, "INPUT", "Email", current="jane@xmple.com"),
        field(2, "INPUT", "Password"),
        field(3, "DROPDOWN", "Country", current="United States"),
        field(4, "OPTION", "Subscribe to newsletter", group="", choice=True),
    ],
    "bug_report": [
        field(0, "INPUT", "Title"),
        field(1, "INPUT", "Steps to reproduce"),
        field(2, "INPUT", "Reporter email"),
        field(3, "DROPDOWN", "Priority", current="Medium"),
        field(4, "OPTION", "Yes", group="Reproducible every time?", choice=True),
        field(5, "OPTION", "No", group="Reproducible every time?", choice=True),
        field(6, "OPTION", "Submit", choice=False),
    ],
}
