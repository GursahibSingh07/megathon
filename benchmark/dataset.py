"""Hand-authored synthetic examples for the voice-form-fill extraction benchmark.

Each entry is one (form, narration) pair with hand-verified ground-truth
actions, grouped into categories that probe the specific rules spelled out
in SlmFormExtractor's prompt (see build_prompt.py) and exercised by
RuleBasedExtractorTest.kt:

  simple_direct              - one clean narration, fields mentioned in screen order
  out_of_order               - fields mentioned in a different order than they appear on screen
  partial                    - narration only covers some of the fields on screen
  no_match                   - nothing said matches any field -> expect []
  spelled_email               - "x at y dot com" must become x@y.com
  correction_typo             - current value is wrong; narration gives the correct one
  no_change_already_correct   - field mentioned again but current is already right -> []
  option_by_own_label         - an OPTION is implied without naming its question/group
  already_selected_option     - the option is already selected -> no redundant click
  submit_button_trap           - narration asks to submit/place order/etc. -> never clicked
  explicit_skip                - narration explicitly says to skip/leave a field blank
  dropdown_close_not_exact     - value for a DROPDOWN is informal/approximate wording
  numbers                      - spoken numbers must become digit strings
  multi_mention_last_wins       - same field mentioned twice; the later value wins

`field_overrides` lets one example tweak a form's base fields (e.g. mark an
option pre-selected) without forking the whole form.
"""

EXAMPLES = [
    # ---------------- simple_direct ----------------
    dict(form="incident_report", category="simple_direct",
         transcript="location is Conference Room 2, severity high, description water leak near the window",
         expected=[
             {"id": 0, "action": "type", "value": "Conference Room 2"},
             {"id": 4, "action": "click"},
             {"id": 1, "action": "type", "value": "water leak near the window"},
         ]),
    dict(form="job_application", category="simple_direct",
         transcript="full name Priya Shah, email priya at example dot com, phone number nine eight seven six five four three two one zero, experience level senior, remote",
         expected=[
             {"id": 0, "action": "type", "value": "Priya Shah"},
             {"id": 1, "action": "type", "value": "priya@example.com"},
             {"id": 2, "action": "type", "value": "9876543210"},
             {"id": 3, "action": "select", "value": "senior"},
             {"id": 4, "action": "click"},
         ]),
    dict(form="event_registration", category="simple_direct",
         transcript="name John Carter, email john at example dot com, two guests, meal preference vegetarian, yes I'm attending",
         expected=[
             {"id": 0, "action": "type", "value": "John Carter"},
             {"id": 1, "action": "type", "value": "john@example.com"},
             {"id": 2, "action": "type", "value": "2"},
             {"id": 3, "action": "select", "value": "vegetarian"},
             {"id": 4, "action": "click"},
         ]),
    dict(form="customer_feedback", category="simple_direct",
         transcript="comments: the staff were very helpful, email mia at example dot com, rating five stars, yes I'd recommend you",
         expected=[
             {"id": 0, "action": "type", "value": "the staff were very helpful"},
             {"id": 1, "action": "type", "value": "mia@example.com"},
             {"id": 2, "action": "select", "value": "five stars"},
             {"id": 3, "action": "click"},
         ]),
    dict(form="medical_intake", category="simple_direct",
         transcript="full name Alex Kim, date of birth March 3rd 1990, known allergies penicillin, blood type O positive, no I don't smoke",
         expected=[
             {"id": 0, "action": "type", "value": "Alex Kim"},
             {"id": 1, "action": "type", "value": "March 3rd 1990"},
             {"id": 2, "action": "type", "value": "penicillin"},
             {"id": 3, "action": "select", "value": "O positive"},
             {"id": 5, "action": "click"},
         ]),
    dict(form="checkout_shipping", category="simple_direct",
         transcript="street address 42 Baker Street, city London, zip code one two three four five, phone number seven seven zero zero one two three four five six, country United Kingdom, express shipping",
         expected=[
             {"id": 0, "action": "type", "value": "42 Baker Street"},
             {"id": 1, "action": "type", "value": "London"},
             {"id": 2, "action": "type", "value": "12345"},
             {"id": 3, "action": "type", "value": "7700123456"},
             {"id": 4, "action": "select", "value": "United Kingdom"},
             {"id": 6, "action": "click"},
         ]),
    dict(form="account_signup", category="simple_direct",
         transcript="username coolcoder99, password Tr0ub4dor!3, country Canada, subscribe me to the newsletter",
         expected=[
             {"id": 0, "action": "type", "value": "coolcoder99"},
             {"id": 2, "action": "type", "value": "Tr0ub4dor!3"},
             {"id": 3, "action": "select", "value": "Canada"},
             {"id": 4, "action": "click"},
         ]),
    dict(form="bug_report", category="simple_direct",
         transcript="title app crashes on launch, steps to reproduce open the app then tap settings, reporter email dev at example dot com, priority high, yes it happens every time",
         expected=[
             {"id": 0, "action": "type", "value": "app crashes on launch"},
             {"id": 1, "action": "type", "value": "open the app then tap settings"},
             {"id": 2, "action": "type", "value": "dev@example.com"},
             {"id": 3, "action": "select", "value": "high"},
             {"id": 4, "action": "click"},
         ]),

    # ---------------- out_of_order ----------------
    dict(form="incident_report", category="out_of_order",
         transcript="severity medium, email sam at example dot com, location parking lot B2",
         expected=[
             {"id": 3, "action": "click"},
             {"id": 5, "action": "type", "value": "sam@example.com"},
             {"id": 0, "action": "type", "value": "parking lot B2"},
         ]),
    dict(form="job_application", category="out_of_order",
         transcript="experience level mid level, full name Dana Scully, onsite please, email dana at example dot com",
         expected=[
             {"id": 3, "action": "select", "value": "mid level"},
             {"id": 0, "action": "type", "value": "Dana Scully"},
             {"id": 6, "action": "click"},
             {"id": 1, "action": "type", "value": "dana@example.com"},
         ]),
    dict(form="event_registration", category="out_of_order",
         transcript="meal preference vegan, yes attending, number of guests three, name Kai Nguyen",
         expected=[
             {"id": 3, "action": "select", "value": "vegan"},
             {"id": 4, "action": "click"},
             {"id": 2, "action": "type", "value": "3"},
             {"id": 0, "action": "type", "value": "Kai Nguyen"},
         ]),
    dict(form="medical_intake", category="out_of_order",
         transcript="blood type A negative, full name Omar Haddad, no smoking, allergies none known",
         expected=[
             {"id": 3, "action": "select", "value": "A negative"},
             {"id": 0, "action": "type", "value": "Omar Haddad"},
             {"id": 5, "action": "click"},
             {"id": 2, "action": "type", "value": "none known"},
         ]),
    dict(form="checkout_shipping", category="out_of_order",
         transcript="standard shipping, country Germany, city Berlin, street address Alexanderplatz 1",
         expected=[
             {"id": 5, "action": "click"},
             {"id": 4, "action": "select", "value": "Germany"},
             {"id": 1, "action": "type", "value": "Berlin"},
             {"id": 0, "action": "type", "value": "Alexanderplatz 1"},
         ]),
    dict(form="bug_report", category="out_of_order",
         transcript="priority low, reproducible every time no, title button does nothing, reporter email qa at example dot com",
         expected=[
             {"id": 3, "action": "select", "value": "low"},
             {"id": 5, "action": "click"},
             {"id": 0, "action": "type", "value": "button does nothing"},
             {"id": 2, "action": "type", "value": "qa@example.com"},
         ]),

    # ---------------- partial ----------------
    dict(form="incident_report", category="partial",
         transcript="description smoke coming from the server room",
         expected=[{"id": 1, "action": "type", "value": "smoke coming from the server room"}]),
    dict(form="job_application", category="partial",
         transcript="phone number five five five one two three four five six seven",
         expected=[{"id": 2, "action": "type", "value": "5551234567"}]),
    dict(form="event_registration", category="partial",
         transcript="we'll have four guests",
         expected=[{"id": 2, "action": "type", "value": "4"}]),
    dict(form="customer_feedback", category="partial",
         transcript="no, I would not recommend this to a friend",
         expected=[{"id": 4, "action": "click"}]),
    dict(form="medical_intake", category="partial",
         transcript="date of birth July 9th 1985",
         expected=[{"id": 1, "action": "type", "value": "July 9th 1985"}]),
    dict(form="checkout_shipping", category="partial",
         transcript="phone number nine one one five five five one two one two",
         expected=[{"id": 3, "action": "type", "value": "9115551212"}]),

    # ---------------- no_match ----------------
    dict(form="incident_report", category="no_match",
         transcript="the weather's been nice lately", expected=[]),
    dict(form="job_application", category="no_match",
         transcript="um, let's see, okay", expected=[]),
    dict(form="event_registration", category="no_match",
         transcript="   ", expected=[]),
    dict(form="medical_intake", category="no_match",
         transcript="hold on, let me think", expected=[]),
    dict(form="bug_report", category="no_match",
         transcript="anyway, moving on", expected=[]),

    # ---------------- spelled_email ----------------
    dict(form="bug_report", category="spelled_email",
         transcript="reporter email is R Priya at test dot org, that's r-p-r-i-y-a at test dot org",
         expected=[{"id": 2, "action": "type", "value": "rpriya@test.org"}]),
    dict(form="job_application", category="spelled_email",
         transcript="email is j smith underscore dev at mail dot co dot uk",
         expected=[{"id": 1, "action": "type", "value": "jsmith_dev@mail.co.uk"}]),
    dict(form="event_registration", category="spelled_email",
         transcript="email is k dot nguyen at example dot net",
         expected=[{"id": 1, "action": "type", "value": "k.nguyen@example.net"}]),
    dict(form="incident_report", category="spelled_email",
         transcript="email is first dot last at example dot org",
         expected=[{"id": 5, "action": "type", "value": "first.last@example.org"}]),
    dict(form="checkout_shipping", category="spelled_email",
         transcript="street address 9 Elm Street",
         expected=[{"id": 0, "action": "type", "value": "9 Elm Street"}]),

    # ---------------- correction_typo ----------------
    dict(form="account_signup", category="correction_typo",
         transcript="sorry, my email is jane at example dot com, spelled e-x-a-m-p-l-e",
         expected=[{"id": 1, "action": "type", "value": "jane@example.com"}]),
    dict(form="job_application", category="correction_typo",
         field_overrides={3: {"current": "Entry level"}},
         transcript="actually put me down as senior level, not entry",
         expected=[{"id": 3, "action": "select", "value": "senior level"}]),
    dict(form="event_registration", category="correction_typo",
         transcript="sorry, make that five guests, not one",
         expected=[{"id": 2, "action": "type", "value": "5"}]),
    dict(form="medical_intake", category="correction_typo",
         transcript="correction, my blood type is B positive",
         expected=[{"id": 3, "action": "select", "value": "B positive"}]),
    dict(form="checkout_shipping", category="correction_typo",
         transcript="wait, change the country to United States",
         expected=[{"id": 4, "action": "select", "value": "United States"}]),

    # ---------------- no_change_already_correct ----------------
    dict(form="account_signup", category="no_change_already_correct",
         transcript="yeah the country's right, leave it", expected=[]),
    dict(form="job_application", category="no_change_already_correct",
         transcript="experience level is fine as is", expected=[]),
    dict(form="event_registration", category="no_change_already_correct",
         transcript="meal preference is fine", expected=[]),
    dict(form="medical_intake", category="no_change_already_correct",
         transcript="not sure about blood type, skip it", expected=[]),
    dict(form="checkout_shipping", category="no_change_already_correct",
         transcript="country is correct, don't change it", expected=[]),

    # ---------------- option_by_own_label ----------------
    dict(form="incident_report", category="option_by_own_label",
         transcript="it was a high priority leak", expected=[{"id": 4, "action": "click"}]),
    dict(form="job_application", category="option_by_own_label",
         transcript="I'd prefer remote work", expected=[{"id": 4, "action": "click"}]),
    dict(form="checkout_shipping", category="option_by_own_label",
         transcript="go with express shipping please", expected=[{"id": 6, "action": "click"}]),
    dict(form="medical_intake", category="option_by_own_label",
         transcript="no, I don't smoke", expected=[{"id": 5, "action": "click"}]),
    dict(form="bug_report", category="option_by_own_label",
         transcript="yes it happens every single time", expected=[{"id": 4, "action": "click"}]),

    # ---------------- already_selected_option ----------------
    dict(form="incident_report", category="already_selected_option",
         field_overrides={2: {"selected": True}},
         transcript="severity low", expected=[]),
    dict(form="job_application", category="already_selected_option",
         field_overrides={4: {"selected": True}},
         transcript="I want to work remote", expected=[]),
    dict(form="event_registration", category="already_selected_option",
         field_overrides={4: {"selected": True}},
         transcript="yes I'll attend", expected=[]),
    dict(form="medical_intake", category="already_selected_option",
         field_overrides={5: {"selected": True}},
         transcript="I don't smoke", expected=[]),
    dict(form="customer_feedback", category="already_selected_option",
         field_overrides={3: {"selected": True}},
         transcript="yes I'd recommend you", expected=[]),

    # ---------------- submit_button_trap ----------------
    dict(form="incident_report", category="submit_button_trap",
         transcript="submit the form now", expected=[]),
    dict(form="job_application", category="submit_button_trap",
         transcript="go ahead and submit my application", expected=[]),
    dict(form="checkout_shipping", category="submit_button_trap",
         transcript="place the order", expected=[]),
    dict(form="bug_report", category="submit_button_trap",
         transcript="please submit this bug report", expected=[]),
    dict(form="incident_report", category="submit_button_trap",
         transcript="location front desk, then submit it",
         expected=[{"id": 0, "action": "type", "value": "front desk"}]),

    # ---------------- explicit_skip ----------------
    dict(form="job_application", category="explicit_skip",
         transcript="skip the phone number for now", expected=[]),
    dict(form="medical_intake", category="explicit_skip",
         transcript="leave allergies blank, I'm not sure", expected=[]),
    dict(form="checkout_shipping", category="explicit_skip",
         transcript="don't fill in the ZIP code yet", expected=[]),
    dict(form="bug_report", category="explicit_skip",
         transcript="leave priority as is", expected=[]),

    # ---------------- dropdown_close_not_exact ----------------
    dict(form="job_application", category="dropdown_close_not_exact",
         transcript="put experience level as senior, I've got a decade of experience",
         expected=[{"id": 3, "action": "select", "value": "senior"}]),
    dict(form="checkout_shipping", category="dropdown_close_not_exact",
         transcript="country, I'm in the US",
         expected=[{"id": 4, "action": "select", "value": "US"}]),
    dict(form="customer_feedback", category="dropdown_close_not_exact",
         transcript="I'd give it a nine out of ten",
         expected=[{"id": 2, "action": "select", "value": "nine out of ten"}]),
    dict(form="bug_report", category="dropdown_close_not_exact",
         transcript="this is pretty urgent, mark it high priority",
         expected=[{"id": 3, "action": "select", "value": "high priority"}]),

    # ---------------- numbers ----------------
    dict(form="event_registration", category="numbers",
         transcript="we're bringing six guests", expected=[{"id": 2, "action": "type", "value": "6"}]),
    dict(form="checkout_shipping", category="numbers",
         transcript="the zip code is nine zero two one zero", expected=[{"id": 2, "action": "type", "value": "90210"}]),
    dict(form="job_application", category="numbers",
         transcript="phone number one one one two two two three three three three",
         expected=[{"id": 2, "action": "type", "value": "1112223333"}]),

    # ---------------- multi_mention_last_wins ----------------
    dict(form="incident_report", category="multi_mention_last_wins",
         transcript="location is Room 1, actually no, location is Room 2",
         expected=[{"id": 0, "action": "type", "value": "Room 2"}]),
    dict(form="account_signup", category="multi_mention_last_wins",
         transcript="username is coolcoder, wait make it coolcoder99",
         expected=[{"id": 0, "action": "type", "value": "coolcoder99"}]),
    dict(form="bug_report", category="multi_mention_last_wins",
         transcript="priority medium, actually make that high",
         expected=[{"id": 3, "action": "select", "value": "high"}]),
]
