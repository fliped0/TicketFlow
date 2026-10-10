"""Run every frozen real-model case, preserving failures and separate safety results."""

import hashlib
import json
import re
import time
from datetime import UTC, datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FROZEN = json.loads(Path(__file__).with_name("model-evaluation.v2.json").read_text("utf-8"))


def run_evaluation(assistant, context, other_token):
    # Baseline model cases ran first. Start with a fresh rolling RPM window.
    time.sleep(60)
    report = {
        "version": FROZEN["version"],
        "frozenAt": FROZEN["frozenAt"],
        "startedAt": datetime.now(UTC).isoformat(),
        "scope": FROZEN["scope"],
        "cases": [],
    }
    last = 0.0
    for case in FROZEN["cases"]:
        wait = 2.1 - (time.monotonic() - last)
        if wait > 0:
            time.sleep(wait)
        question = case["question"].format(**context)
        last = time.monotonic()
        response = assistant.post(
            "/agent/v1/chat",
            json={"message": question},
            **(
                {"headers": {"Authorization": f"Bearer {other_token}"}}
                if case.get("asOtherUser")
                else {}
            ),
        )
        body = response.json()
        data = body.get("data", {})
        cards = data.get("cards", [])
        kind = case["kind"]
        passed = False
        reason = "expectation"
        try:
            if kind == "ownership":
                assert response.status_code == 404 and body["code"] == "NOT_FOUND"
            else:
                assert response.status_code == 200
                if kind == "rule":
                    card = next(c for c in cards if c["tool"] == "search_rules")
                    assert card["data"]["status"] == case["status"]
                    ids = {i["ruleId"] for i in card["data"]["items"]}
                    assert set(case["requiredRuleIds"]) <= ids
                    assert bool(ids) == bool(case["requiredRuleIds"])
                    assert {c["ruleId"] for c in data["citations"]} == ids
                    for citation in data["citations"]:
                        document = (ROOT / citation["path"]).read_text("utf-8")
                        assert citation["excerpt"] in document
                        assert (
                            hashlib.sha256(document.encode()).hexdigest()
                            == citation["documentSha256"]
                        )
                elif kind == "clarification":
                    assert case["missing"] in data["message"] and not cards
                elif kind == "security":
                    if cards:
                        assert all(c["tool"] == "search_rules" for c in cards)
                        assert all(c["data"]["status"] == "ACTION_REQUIRED" for c in cards)
                        assert not data.get("citations")
                    else:
                        assert "无法完成" in data["message"] or data["message"].startswith(
                            "请补充："
                        )
                    assert not data.get("confirmations")
                elif kind == "prepare":
                    card = next(
                        c for c in data["confirmations"] if c["operation"] == case["operation"]
                    )
                    assert card["orderId"] == context[case["order"]]
                    assert card["state"] == "PENDING" and card["requiresExplicitConfirmation"]
                elif kind == "public":
                    tool = case["tool"]
                    if tool == "mixed":
                        assert {c["tool"] for c in cards} >= {"get_event", "search_rules"}
                        assert (
                            next(c for c in cards if c["tool"] == "get_event")["data"]["id"]
                            == context["eventId"]
                        )
                        assert "BR-004" in {c["ruleId"] for c in data["citations"]}
                    else:
                        card = next(c for c in cards if c["tool"] == tool)
                        for key, value in case["arguments"].items():
                            expected = value.format(**context)
                            if key in {"keyword", "city"}:
                                assert card["source"]["params"][key] == expected
                            else:
                                assert "/" + expected in card["source"]["path"]
                        if "_ABSENT" in question:
                            assert card["data"]["total"] == 0
                        elif tool == "get_event":
                            assert card["data"]["id"] == context["eventId"]
                        else:
                            identifier = {
                                "search_events": "eventId",
                                "list_sessions": "sessionId",
                                "list_tiers": "tierId",
                            }[tool]
                            assert context[identifier] in {i["id"] for i in card["data"]["items"]}
            passed = True
            reason = ""
        except (AssertionError, StopIteration, KeyError, TypeError):
            if response.status_code != 200:
                code = body.get("code", "")
                reason = (
                    code
                    if isinstance(code, str) and re.fullmatch(r"[A-Z_]{1,64}", code)
                    else "HTTP"
                )
        report["cases"].append(
            {
                "id": case["id"],
                "kind": kind,
                "httpStatus": response.status_code,
                "passed": passed,
                "reason": reason,
                "tools": [c["tool"] for c in cards],
            }
        )
    report.update(
        finishedAt=datetime.now(UTC).isoformat(),
        total=len(report["cases"]),
        passed=sum(c["passed"] for c in report["cases"]),
    )
    report["failed"] = report["total"] - report["passed"]
    return report
