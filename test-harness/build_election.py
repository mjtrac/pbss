#!/usr/bin/env python3
"""
build_election.py — Drives bBuilder via the /api/test REST API to create
a multi-precinct, multi-party, multi-page 8.5x14 test election with all
contest and candidate types.

Usage:  python build_election.py [--host http://localhost:8080]

Returns (via stdout and election_data.json):
  - jurisdiction/election/region/party IDs
  - list of (combinationId, yamlFile, pdfFile) per precinct×party
"""
import argparse, json, sys, time
import requests

# ── Config ────────────────────────────────────────────────────────────────────

DEFAULT_HOST = "http://localhost:8080"
CREDENTIALS  = ("admin", "ChangeMe123!")

def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument("--host", default=DEFAULT_HOST)
    p.add_argument("--indicator-type", default="OVAL",
                   choices=["OVAL", "CHECKBOX", "CONNECT_DOTS"],
                   dest="indicator_type",
                   help="Vote indicator style (default: OVAL)")
    p.add_argument("--out",  default="election_data.json")
    return p.parse_args()

# ── API client ────────────────────────────────────────────────────────────────

class BBuilderClient:
    def __init__(self, host):
        self.host = host.rstrip("/")
        self.session = requests.Session()
        self.session.auth = CREDENTIALS   # HTTP Basic — but bBuilder uses form login
        self._login()

    def _login(self):
        """Log in via the form and hold the session cookie."""
        import re
        r = self.session.get(f"{self.host}/login")
        r.raise_for_status()
        m = re.search(r'name="_csrf"[^>]+value="([^"]+)"', r.text)
        csrf = m.group(1) if m else ""
        r2 = self.session.post(f"{self.host}/login", data={
            "username": CREDENTIALS[0],
            "password": CREDENTIALS[1],
            "_csrf":    csrf,
        }, allow_redirects=True)
        if "logout" not in r2.text.lower() and "dashboard" not in r2.text.lower():
            raise RuntimeError("Login failed — check credentials and that bBuilder is running")
        print(f"  ✓ Logged in to bBuilder (session cookies: {list(self.session.cookies.keys())})")

        # Fetch CSRF token from the dashboard and add to session headers
        # Spring Security requires X-CSRF-TOKEN on non-GET requests
        r_csrf = self.session.get(f"{self.host}/dashboard")
        import re as _re
        m2 = _re.search(r'name="_csrf"[^>]+value="([^"]+)"', r_csrf.text)
        if m2:
            self.csrf_token = m2.group(1)
            self.session.headers.update({"X-CSRF-TOKEN": self.csrf_token})
            print(f"  ✓ CSRF token acquired")
        else:
            # Try meta tag format
            m3 = _re.search(r'content="([^"]+)"[^>]+name="[^"]*csrf[^"]*"', r_csrf.text)
            if m3:
                self.csrf_token = m3.group(1)
                self.session.headers.update({"X-CSRF-TOKEN": self.csrf_token})
                print(f"  ✓ CSRF token acquired (meta)")
            else:
                print(f"  ⚠  Could not find CSRF token — POSTs may fail with 403")
                self.csrf_token = ""

        # Verify the API endpoint is reachable — gives a clear error if controller missing
        probe = self.session.get(f"{self.host}/api/test/ping", allow_redirects=False)
        print(f"  API probe: HTTP {probe.status_code}  body={probe.text[:120]!r}")
        if probe.status_code == 302:
            raise RuntimeError(
                f"GET /api/test/ping returned 302 redirect to {probe.headers.get('Location')}\n"
                "This means either:\n"
                "  (a) The session cookie was not accepted — Spring Security redirected to login\n"
                "  (b) TestApiController is not loaded — bBuilder must be restarted with\n"
                "      ./mvnw clean spring-boot:run  AFTER the new Java file was placed in src/"
            )
        if probe.status_code == 404:
            raise RuntimeError(
                "GET /api/test/ping returned 404 — TestApiController is not registered.\n"
                "Restart bBuilder:  cd bBuilder && ./mvnw clean spring-boot:run"
            )

    def _url(self, path):
        return f"{self.host}/api/test/{path}"

    def ping(self):
        r = self.session.get(self._url("ping"))
        if not r.ok or not r.text.strip():
            raise RuntimeError(
                f"GET /api/test/ping failed: HTTP {r.status_code}\n"
                f"Body: {r.text[:500]!r}\n"
                f"URL:  {r.url}\n"
                "Ensure bBuilder was restarted AFTER TestApiController.java was added "
                "and that ./mvnw clean spring-boot:run completed without errors."
            )
        return r.json()

    def reset(self):
        return self.session.delete(self._url("reset")).json()

    def post(self, path, body):
        r = self.session.post(self._url(path), json=body)
        if not r.ok or not r.text.strip():
            raise RuntimeError(
                f"POST /api/test/{path} failed HTTP {r.status_code}\n"
                f"Body: {r.text[:500]!r}"
            )
        return r.json()

    def generate(self, combo_id, template_id=None):
        params = {"templateId": template_id} if template_id else {}
        r = self.session.post(self._url(f"generate/{combo_id}"), params=params)
        if not r.ok:
            raise RuntimeError(f"generate {combo_id} failed {r.status_code}: {r.text[:300]}")
        return r.json()

    def combinations(self):
        return self.session.get(self._url("combinations")).json()

# ── Election definition ───────────────────────────────────────────────────────

def build(client, out_path):
    print("\n── Resetting existing test data ──────────────────────────────")
    client.reset()
    print("  ✓ Reset complete")

    # ── Jurisdiction
    print("\n── Creating jurisdiction & election ──────────────────────────")
    jur = client.post("jurisdiction", {"name": "Test County"})
    print(f"  ✓ Jurisdiction: {jur['name']} (id={jur['id']})")

    elec = client.post("election", {
        "jurisdictionId": jur["id"],
        "name":           "Test General Election 2026",
        "electionDate":   "2026-11-03",
    })
    print(f"  ✓ Election: {elec['name']} (id={elec['id']})")

    # ── Ballot types
    print("\n── Creating ballot types ─────────────────────────────────────")
    bt_precinct  = client.post("ballot-type", {"name": "Precinct",  "jurisdictionId": jur["id"]})
    bt_mail      = client.post("ballot-type", {"name": "Mail-In",  "jurisdictionId": jur["id"]})
    print(f"  ✓ BallotTypes: {bt_precinct['name']}, {bt_mail['name']}")

    # ── Parties
    print("\n── Creating parties ──────────────────────────────────────────")
    party_np  = client.post("party", {"name": "Nonpartisan", "abbreviation": "NP",  "jurisdictionId": jur["id"]})
    party_dem = client.post("party", {"name": "Progressive", "abbreviation": "PRG", "jurisdictionId": jur["id"]})
    print(f"  ✓ Parties: {party_np['name']}, {party_dem['name']}")

    # ── Regions — 3 precincts + 1 group
    print("\n── Creating regions ──────────────────────────────────────────")
    p1 = client.post("region", {"name": "Precinct 1", "type": "SINGLE_PRECINCT", "jurisdictionId": jur["id"]})
    p2 = client.post("region", {"name": "Precinct 2", "type": "SINGLE_PRECINCT", "jurisdictionId": jur["id"]})
    p3 = client.post("region", {"name": "Precinct 3", "type": "SINGLE_PRECINCT", "jurisdictionId": jur["id"]})
    g_all = client.post("region", {
        "name":           "All Precincts",
        "type":           "PRECINCT_GROUP",
        "groupType":      "PrecinctGroup",
        "memberIds":      [p1["id"], p2["id"], p3["id"]],
        "jurisdictionId": jur["id"],
    })
    g12 = client.post("region", {
        "name":           "Precincts 1-2",
        "type":           "PRECINCT_GROUP",
        "groupType":      "PrecinctGroup",
        "memberIds":      [p1["id"], p2["id"]],
        "jurisdictionId": jur["id"],
    })
    print(f"  ✓ Regions: P1={p1['id']}, P2={p2['id']}, P3={p3['id']}, "
          f"G-All={g_all['id']}, G12={g12['id']}")

    # ── Contests (all regions = g_all so all appear on every ballot)
    print("\n── Creating contests ─────────────────────────────────────────")
    all_regions = [g_all["id"]]
    g12_regions = [g12["id"]]
    p3_regions  = [p3["id"]]

    contests = {}

    # 1. Federal — Ranked Choice, 4 ranks, sectionHeader, preamble, postamble
    c = client.post("contest", {
        "title":         "President of the United States",
        "electionId":    elec["id"],
        "contestType":   "RANKED_CHOICE",
        "maxVotes":      4,
        "sectionHeader": "FEDERAL OFFICES",
        "instruction":   "Fill the larger box closest to the candidate for your first choice, and fill smaller boxes for your lower choices. The farther away the box, the lower the rank.",
        "preamble":      "The President serves a four-year term. You may rank up to "
                         "four candidates. The candidate with the most top-rank votes wins, "
                         "subject to runoff procedures.",
        "postamble":     "Write-in candidates must be registered to be counted.",
        "regionIds":     all_regions,
    })
    contests["president"] = c["id"]
    print(f"  ✓ President (Ranked Choice) id={c['id']}")

    # 2. Congressional — Plurality, vote for 1, no preamble
    c = client.post("contest", {
        "title":       "Representative in Congress",
        "electionId":  elec["id"],
        "contestType": "PLURALITY",
        "maxVotes":    1,
        "instruction": "Vote for one.",
        "regionIds":   all_regions,
    })
    contests["congress"] = c["id"]
    print(f"  ✓ Representative in Congress (Plurality/1) id={c['id']}")

    # 3. State Senate — Plurality vote for 1 (P1+P2 only)
    c = client.post("contest", {
        "title":         "State Senator",
        "electionId":    elec["id"],
        "contestType":   "PLURALITY",
        "maxVotes":      1,
        "sectionHeader": "STATE OFFICES",
        "instruction":   "Vote for one.",
        "preamble":      "Represents districts 1 and 2 in the State Senate.",
        "regionIds":     g12_regions,
    })
    contests["state_senate"] = c["id"]
    print(f"  ✓ State Senator (Plurality/1, P1+P2) id={c['id']}")

    # 4. City Council — Plurality vote for 3 (multi-winner)
    c = client.post("contest", {
        "title":         "City Council Member",
        "electionId":    elec["id"],
        "contestType":   "PLURALITY",
        "maxVotes":      3,
        "sectionHeader": "LOCAL OFFICES",
        "instruction":   "Vote for up to three.",
        "postamble":     "The three candidates with the most votes will be elected.",
        "regionIds":     all_regions,
    })
    contests["city_council"] = c["id"]
    print(f"  ✓ City Council (Plurality/3) id={c['id']}")

    # 5. Mayor — Approval voting
    c = client.post("contest", {
        "title":       "Mayor",
        "electionId":  elec["id"],
        "contestType": "APPROVAL",
        "maxVotes":    99,
        "instruction": "Mark all candidates you approve of.",
        "preamble":    "Approval voting: you may vote for as many candidates as you wish. "
                       "The candidate approved by the most voters wins.",
        "regionIds":   all_regions,
    })
    contests["mayor"] = c["id"]
    print(f"  ✓ Mayor (Approval) id={c['id']}")

    # 6. School Board — Plurality vote for 2 (P3 only)
    c = client.post("contest", {
        "title":       "School Board Director",
        "electionId":  elec["id"],
        "contestType": "PLURALITY",
        "maxVotes":    2,
        "instruction": "Vote for up to two.",
        "regionIds":   p3_regions,
    })
    contests["school_board"] = c["id"]
    print(f"  ✓ School Board (Plurality/2, P3) id={c['id']}")

    # 7. Measure A — Yes/No measure with full preamble and postamble
    c = client.post("contest", {
        "title":         "Measure A — Infrastructure Bond",
        "electionId":    elec["id"],
        "contestType":   "MEASURE",
        "maxVotes":      1,
        "sectionHeader": "MEASURES",
        "preamble":      "Shall the County of Test be authorized to issue general obligation "
                         "bonds in an amount not to exceed $50,000,000 for the purpose of "
                         "repairing and improving roads, bridges, and public facilities? "
                         "Annual taxes sufficient to pay the principal and interest on the "
                         "bonds will be levied.",
        "postamble":     "A YES vote authorizes issuance of the bonds. "
                         "A NO vote rejects bond issuance. "
                         "Requires 55% approval to pass.",
        "regionIds":     all_regions,
    })
    contests["measure_a"] = c["id"]
    print(f"  ✓ Measure A (Yes/No) id={c['id']}")

    # 8. Measure B — Simple advisory measure (P3 only)
    c = client.post("contest", {
        "title":       "Measure B — Advisory Vote on Library Hours",
        "electionId":  elec["id"],
        "contestType": "MEASURE",
        "maxVotes":    1,
        "preamble":    "Shall the County extend library hours to include Sunday afternoons?",
        "regionIds":   p3_regions,
    })
    contests["measure_b"] = c["id"]
    print(f"  ✓ Measure B (Advisory, P3) id={c['id']}")

    # 9. County Assessor — Plurality/1 (all precincts)
    c = client.post("contest", {
        "title":         "County Assessor",
        "electionId":    elec["id"],
        "contestType":   "PLURALITY",
        "maxVotes":      1,
        "sectionHeader": "COUNTY OFFICES",
        "instruction":   "Vote for one.",
        "regionIds":     all_regions,
    })
    contests["assessor"] = c["id"]
    print(f"  ✓ County Assessor (Plurality/1) id={c['id']}")

    # 10. County Treasurer — Plurality/1
    c = client.post("contest", {
        "title":       "County Treasurer",
        "electionId":  elec["id"],
        "contestType": "PLURALITY",
        "maxVotes":    1,
        "instruction": "Vote for one.",
        "regionIds":   all_regions,
    })
    contests["treasurer"] = c["id"]
    print(f"  ✓ County Treasurer (Plurality/1) id={c['id']}")

    # 11. District Attorney — Plurality/1 with preamble
    c = client.post("contest", {
        "title":       "District Attorney",
        "electionId":  elec["id"],
        "contestType": "PLURALITY",
        "maxVotes":    1,
        "instruction": "Vote for one.",
        "preamble":    "The District Attorney serves a four-year term and is "
                       "the chief law enforcement officer of the county.",
        "regionIds":   all_regions,
    })
    contests["da"] = c["id"]
    print(f"  ✓ District Attorney (Plurality/1) id={c['id']}")

    # 12. Superior Court Judge — Plurality/1 (judicial retention)
    c = client.post("contest", {
        "title":         "Superior Court Judge, Seat 3",
        "electionId":    elec["id"],
        "contestType":   "PLURALITY",
        "maxVotes":      1,
        "sectionHeader": "JUDICIAL",
        "instruction":   "Vote for one.",
        "preamble":      "Shall the following judge of the Superior Court be retained "
                         "in office for a six-year term?",
        "regionIds":     all_regions,
    })
    contests["judge"] = c["id"]
    print(f"  ✓ Superior Court Judge (Plurality/1) id={c['id']}")

    # 13. Water Board — Approval (all precincts, 3 seats)
    c = client.post("contest", {
        "title":       "Water District Board Member",
        "electionId":  elec["id"],
        "contestType": "APPROVAL",
        "maxVotes":    99,
        "instruction": "Vote for up to three.",
        "postamble":   "The three candidates receiving the most approval votes will be seated.",
        "regionIds":   all_regions,
    })
    contests["water_board"] = c["id"]
    print(f"  ✓ Water Board (Approval) id={c['id']}")

    # 14. Measure C — Tax measure (P1+P2)
    c = client.post("contest", {
        "title":         "Measure C — Local Road Tax",
        "electionId":    elec["id"],
        "contestType":   "MEASURE",
        "maxVotes":      1,
        "preamble":      "Shall Precincts 1 and 2 levy an additional $0.01 per square "
                         "foot annual tax on commercial property to fund local road "
                         "repair, with citizen oversight and independent auditing?",
        "postamble":     "Requires two-thirds (2/3) approval to pass.",
        "regionIds":     g12_regions,
    })
    contests["measure_c"] = c["id"]
    print(f"  ✓ Measure C (P1+P2) id={c['id']}")

    # 15. Ranked Choice — County Executive (to force page 2 overflow)
    c = client.post("contest", {
        "title":         "County Executive",
        "electionId":    elec["id"],
        "contestType":   "RANKED_CHOICE",
        "maxVotes":      3,
        "sectionHeader": "COUNTY OFFICES",
        "instruction":   "Rank your top three choices.",
        "preamble":      "The County Executive oversees daily county operations. "
                         "Rank up to three candidates in order of preference.",
        "regionIds":     all_regions,
    })
    contests["county_exec"] = c["id"]
    print(f"  ✓ County Executive (Ranked/3) id={c['id']}")

    # ── Candidates ────────────────────────────────────────────────────────────
    print("\n── Creating candidates ───────────────────────────────────────")

    # President (ranked choice — 5 candidates + write-in)
    pres_cands = [
        {"name": "Alexandria Washington",  "explanatoryText": "Current Vice President"},
        {"name": "Benjamin Adams",         "explanatoryText": "Former Secretary of State"},
        {"name": "Carolina Jefferson",     "explanatoryText": "Governor, Eastern State",
         "suffix": "Ph.D."},
        {"name": "Douglas Madison",        "explanatoryText": None},
        {"name": "Eleanor Monroe",         "explanatoryText": "U.S. Senator"},
        {"name": "Write-In",               "writeIn": True},
    ]
    pres_ids = []
    for cd in pres_cands:
        body = {"contestId": contests["president"], "name": cd["name"],
                "writeIn": cd.get("writeIn", False)}
        if cd.get("explanatoryText"):
            body["explanatoryText"] = cd["explanatoryText"]
        if cd.get("suffix"):
            body["suffix"] = cd["suffix"]
        r = client.post("candidate", body)
        pres_ids.append(r["id"])
    print(f"  ✓ President candidates: {len(pres_ids)} including write-in")

    # Congress (5 candidates + write-in, one with prefix)
    cong_cands = [
        {"name": "Alice Smith"},
        {"name": "Bill Jones",     "explanatoryText": "Incumbent"},
        {"name": "Chuck Edwards",  "prefix": "Dr."},
        {"name": "Dorothy Johnson","explanatoryText": "Community Organizer"},
        {"name": "Ernest Garcia",  "explanatoryText": "Business Owner"},
        {"name": "Write-In",       "writeIn": True},
    ]
    cong_ids = []
    for cd in cong_cands:
        body = {"contestId": contests["congress"], "name": cd["name"],
                "writeIn": cd.get("writeIn", False)}
        if cd.get("explanatoryText"): body["explanatoryText"] = cd["explanatoryText"]
        if cd.get("prefix"):          body["prefix"]          = cd["prefix"]
        r = client.post("candidate", body)
        cong_ids.append(r["id"])
    print(f"  ✓ Congress candidates: {len(cong_ids)}")

    # State Senate (3 candidates + write-in)
    senate_ids = []
    for name in ["Patricia Chen", "Robert Williams", "Sandra Okafor", "Write-In"]:
        r = client.post("candidate", {
            "contestId": contests["state_senate"],
            "name": name,
            "writeIn": name == "Write-In",
        })
        senate_ids.append(r["id"])
    print(f"  ✓ State Senate candidates: {len(senate_ids)}")

    # City Council (6 candidates + write-in — enough to test multi-winner)
    council_cands = [
        {"name": "Anna Park",        "explanatoryText": "District 1 Rep"},
        {"name": "Brian Foster",     "explanatoryText": "District 2 Rep"},
        {"name": "Carmen Lopez",     "explanatoryText": "District 3 Rep"},
        {"name": "David Kim",        "explanatoryText": "At-Large"},
        {"name": "Eva Patel",        "explanatoryText": "Incumbent, District 4"},
        {"name": "Frank Nguyen"},
        {"name": "Write-In",         "writeIn": True},
    ]
    council_ids = []
    for cd in council_cands:
        body = {"contestId": contests["city_council"], "name": cd["name"],
                "writeIn": cd.get("writeIn", False)}
        if cd.get("explanatoryText"): body["explanatoryText"] = cd["explanatoryText"]
        r = client.post("candidate", body)
        council_ids.append(r["id"])
    print(f"  ✓ City Council candidates: {len(council_ids)}")

    # Mayor (Approval — 4 candidates, no write-in for approval contests)
    mayor_cands = ["Bill de Blasio", "Eric Adams", "Zohran Mamdani", "Nina Turner"]
    mayor_ids = []
    for name in mayor_cands:
        r = client.post("candidate", {"contestId": contests["mayor"], "name": name})
        mayor_ids.append(r["id"])
    print(f"  ✓ Mayor candidates: {len(mayor_ids)}")

    # School Board (4 candidates + write-in)
    school_ids = []
    for name in ["Grace Lee", "Henry Brown", "Iris Martinez", "James Wilson", "Write-In"]:
        r = client.post("candidate", {
            "contestId": contests["school_board"],
            "name": name,
            "writeIn": name == "Write-In",
        })
        school_ids.append(r["id"])
    print(f"  ✓ School Board candidates: {len(school_ids)}")

    # Measure A
    for name in ["Yes", "No"]:
        client.post("candidate", {"contestId": contests["measure_a"], "name": name})
    print(f"  ✓ Measure A options: Yes/No")

    # County Assessor
    for name in ["Linda Park", "Michael Torres", "Write-In"]:
        client.post("candidate", {"contestId": contests["assessor"], "name": name,
                                   "writeIn": name == "Write-In"})
    print(f"  ✓ County Assessor candidates: 3")

    # County Treasurer
    for name in ["Sarah Hoffman", "James Reed", "Write-In"]:
        client.post("candidate", {"contestId": contests["treasurer"], "name": name,
                                   "writeIn": name == "Write-In"})
    print(f"  ✓ County Treasurer candidates: 3")

    # District Attorney
    for name in ["Rachel Kim", "Thomas Brennan", "Maria Santos", "Write-In"]:
        client.post("candidate", {"contestId": contests["da"], "name": name,
                                   "writeIn": name == "Write-In"})
    print(f"  ✓ District Attorney candidates: 4")

    # Superior Court Judge — Yes/No retention
    for name in ["Yes", "No"]:
        client.post("candidate", {"contestId": contests["judge"], "name": name})
    print(f"  ✓ Superior Court Judge: Yes/No retention")

    # Water Board (Approval — 5 candidates)
    for name in ["Chen Wei", "Barbara Scott", "Ahmed Hassan",
                  "Lucy Fernandez", "Tom Nakamura"]:
        client.post("candidate", {"contestId": contests["water_board"], "name": name})
    print(f"  ✓ Water Board candidates: 5")

    # Measure C
    for name in ["Yes", "No"]:
        client.post("candidate", {"contestId": contests["measure_c"], "name": name})
    print(f"  ✓ Measure C options: Yes/No")

    # County Executive (Ranked Choice — 4 candidates + write-in)
    for name in ["Victoria Chang", "Samuel Obi", "Laura Reyes",
                  "Marcus Hill", "Write-In"]:
        client.post("candidate", {"contestId": contests["county_exec"], "name": name,
                                   "writeIn": name == "Write-In"})
    print(f"  ✓ County Executive candidates: 5")

    # Measure B
    for name in ["Yes", "No"]:
        client.post("candidate", {"contestId": contests["measure_b"], "name": name})
    print(f"  ✓ Measure B options: Yes/No")

    # ── Ballot design template 1: standard 8.5×14, 3 columns ─────────────────
    print("\n── Creating ballot templates ─────────────────────────────────")
    tmpl = client.post("template", {
        "electionId":      elec["id"],
        "paperSize":       "LEGAL_8_5x14",
        "columns":         3,
        "indicatorType":   args.indicator_type,
        "barcodeHeightPt": 72,    # 1" QR code — no linear barcode
        "barcodeWidthPt":  0,
        "headerHtml": (
            "<div style=\"font-family:Helvetica,Arial,sans-serif;padding:4px 0\">"
            "<p style=\"font-size:13pt;font-weight:bold;margin:0 0 4px 0\">OFFICIAL TEST BALLOT</p>"
            "<p style=\"font-size:9pt;margin:0 0 2px 0\">{jurisdictionName}</p>"
            "<p style=\"font-size:9pt;margin:0 0 6px 0\">{electionName}</p>"
            "<p style=\"font-size:9pt;margin:0\">To vote, completely fill in the "
            "{indicatorName} next to your choice.</p>"
            "</div>"
        ),
    })
    print(f"  ✓ Template 1: 8.5×14 legal, 3 columns (id={tmpl['id']})")

    # ── Ballot design template 2: large header, custom fonts/margins ───────────
    # This template tests that bCounter is not thrown by non-default design choices:
    # - Large header block (~3 inches of header text and instructions)
    # - Non-default margins, font sizes, and column count
    # - Same contests and precincts as template 1 but different layout
    LARGE_HEADER_TEXT = (
        "OFFICIAL BALLOT\n"
        "Test General Election 2026\n\n"
        "VOTER INSTRUCTIONS\n"
        "To vote: completely fill in the oval next to your choice.\n"
        "To write in a candidate: fill in the oval and write the name "
        "on the line provided.\n"
        "If you make a mistake: ask a poll worker for a new ballot.\n\n"
        "This ballot contains contests for Federal, State, County, "
        "and Local offices. Please review both sides of this ballot "
        "before submitting. Ballots cannot be returned once submitted."
    )
    tmpl2 = client.post("template", {
        "electionId":           elec["id"],
        "paperSize":            "LEGAL_8_5x14",
        "columns":              2,           # 2 columns instead of 3
        "indicatorType":        "OVAL",
        "marginTopPt":          216,         # 3 inches top margin for large header
        "marginBottomPt":       54,          # 0.75 inch bottom
        "marginLeftPt":         54,          # 0.75 inch left
        "marginRightPt":        54,          # 0.75 inch right
        "contestTitleFontSize": 13,          # larger than default 11
        "candidateNameFontSize": 11,         # larger than default 9
        "instructionFontSize":  8,           # smaller than default 9
        "headerFontSize":       8,
        "contestTitleBold":     True,
        "barcodeHeightPt":       72,
        "barcodeWidthPt":        0,
        "headerHtml": (
            "<div style=\"font-family:Helvetica,Arial,sans-serif;padding:4px 0\">"
            "<p style=\"font-size:13pt;font-weight:bold;margin:0 0 4px 0\">OFFICIAL BALLOT — LARGE HEADER TEST</p>"
            + "".join(
                f"<p style=\"font-size:9pt;margin:0 0 2px 0\">{para}</p>"
                for para in LARGE_HEADER_TEXT.strip().split("\n") if para.strip()
            )
            + "</div>"
        ),
    })
    print(f"  ✓ Template 2: 8.5×14, 2 columns, 3-inch header, custom fonts (id={tmpl2['id']})")

    # ── Ballot combinations (3 precincts × 2 parties) ─────────────────────────
    print("\n── Creating ballot combinations ──────────────────────────────")
    combos = []
    for precinct in [p1, p2, p3]:
        for party in [party_np, party_dem]:
            bc = client.post("combination", {
                "regionId":    precinct["id"],
                "partyId":     party["id"],
                "ballotTypeId":bt_precinct["id"],
                "electionId":  elec["id"],
            })
            combos.append({
                "combinationId": bc["id"],
                "precinct":      precinct["name"],
                "precinctId":    precinct["id"],
                "party":         party["name"],
                "partyId":       party["id"],
            })
            print(f"  ✓ Combo {bc['id']}: {precinct['name']} + {party['name']}")

    # ── Generate ballots ───────────────────────────────────────────────────────
    print("\n── Generating ballot PDFs and layout files ───────────────────")
    for bc in combos:
        result = client.generate(bc["combinationId"])
        bc["files"] = result.get("files", [])
        bc["yamlFiles"] = [f for f in bc["files"] if f.endswith(".yaml")]
        bc["pdfFiles"]  = [f for f in bc["files"] if f.endswith(".pdf")]
        print(f"  ✓ Combo {bc['combinationId']}: {len(bc['files'])} files written")
        for f in bc["files"]:
            print(f"      {f}")

    # ── Write election data JSON ───────────────────────────────────────────────
    data = {
        "jurisdiction": jur,
        "election":     elec,
        "parties":      [party_np, party_dem],
        "precincts":    [p1, p2, p3],
        "contests":     contests,
        "combinations": combos,
    }
    with open(out_path, "w") as f:
        json.dump(data, f, indent=2)
    # ── Large-header ballot generation for Precinct 1 combos ────────────────
    # Re-generate Precinct 1 combinations with the large-header template.
    # This tests that bCounter correctly handles non-default margins, fonts,
    # and a ~3-inch header block.
    print("\n── Generating large-header ballots (Precinct 1) ──────────────")
    lh_combos = []
    for combo in [c for c in combos if c["precinct"] == "Precinct 1"]:
        files = client.generate(combo["combinationId"], template_id=tmpl2["id"])
        lh_combo = dict(combo)
        lh_combo["combinationId"] = f"lh_{combo['combinationId']}"
        lh_combo["precinct"]      = combo["precinct"] + " (large-header)"
        lh_combo["files"]         = files.get("files", [])
        lh_combo["yamlFiles"]     = [f for f in files.get("files", []) if f.endswith(".yaml")]
        lh_combo["pdfFiles"]      = [f for f in files.get("files", []) if f.endswith(".pdf")]
        lh_combos.append(lh_combo)
        print(f"  ✓ Large-header {combo['precinct']} + {combo['party']}: {len(lh_combo['files'])} files")
    combos.extend(lh_combos)

    print(f"\n── Election data saved to {out_path}")
    return data


if __name__ == "__main__":
    args = parse_args()
    print(f"bBuilder at {args.host}")
    client = BBuilderClient(args.host)
    ping = client.ping()
    print(f"  ✓ Ping: {ping}")
    data = build(client, args.out)
    print(f"\nDone. {len(data['combinations'])} ballot combinations generated.")
