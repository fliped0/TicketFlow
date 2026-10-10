"""Actual HTTP/DB confirmations; synthetic fixtures only, no model approvals."""

import time
from datetime import UTC, datetime, timedelta

from model_evaluation import run_evaluation


def run_confirmations(
    api,
    assistant,
    business,
    run_id,
    event,
    order_id,
    tokens,
    admin_token,
    *,
    evaluation=False,
    faults=False,
):
    report = {"checks": []}
    original = business(api, "GET", f"/api/v1/orders/{order_id}")
    admin_headers = {"Authorization": f"Bearer {admin_token}"}
    sale = datetime.now(UTC) + timedelta(seconds=3)
    starts = sale + timedelta(days=2)
    paid_event = business(
        api,
        "POST",
        "/api/v1/admin/events",
        headers=admin_headers,
        json={
            "name": "AgentPaid_" + run_id,
            "description": "隔离退款测试",
            "category": "TEST",
            "city": "杭州",
            "venue": "隔离测试场地",
        },
    )
    session = business(
        api,
        "POST",
        f"/api/v1/admin/events/{paid_event['id']}/sessions",
        headers=admin_headers,
        json={
            "startsAt": starts.isoformat(),
            "saleStartAt": sale.isoformat(),
            "saleEndAt": (sale + timedelta(days=1)).isoformat(),
        },
    )
    tier = business(
        api,
        "POST",
        f"/api/v1/admin/sessions/{session['id']}/tiers",
        headers=admin_headers,
        json={"name": "AgentPaid", "priceFen": 4321, "capacity": 3},
    )
    business(
        api,
        "PUT",
        f"/api/v1/admin/events/{paid_event['id']}/status",
        headers=admin_headers,
        json={"status": "ON_SALE", "expectedVersion": paid_event["version"]},
    )
    time.sleep(max(0, (sale - datetime.now(UTC)).total_seconds()) + 0.1)
    paid_order = business(
        api,
        "POST",
        "/api/v1/orders",
        headers={"Idempotency-Key": "c_create_" + run_id},
        json={"tierId": tier["id"], "quantity": 1},
    )["orderId"]
    business(
        api,
        "POST",
        f"/api/v1/orders/{paid_order}/payments",
        json={},
        headers={"Idempotency-Key": "c_pay_" + run_id},
    )
    try:
        if evaluation:
            context = {
                "keyword": "AgentSmoke_" + run_id,
                "eventId": event["id"],
                "orderId": order_id,
                "paidOrderId": paid_order,
                "sessionId": original["snapshot"]["sessionId"],
                "tierId": original["snapshot"]["tierId"],
            }
            report["modelEvaluation"] = run_evaluation(assistant, context, tokens[1])
            assert business(api, "GET", f"/api/v1/orders/{order_id}")["status"] == "PENDING"
            assert business(api, "GET", f"/api/v1/orders/{paid_order}")["status"] == "PAID"
            report["modelEvaluation"]["unconfirmedWrites"] = 0
            report["checks"].append(
                {"name": "model_evaluation_unconfirmed_zero_writes", "passed": True}
            )
        for operation, target in [("cancel", order_id), ("refund", paid_order)]:
            confirmation = business(
                assistant,
                "POST",
                "/agent/v1/confirmations",
                json={"operation": operation, "orderId": target},
            )
            identifier = confirmation["confirmationId"]
            assert confirmation["state"] == "PENDING" and confirmation["simulation"]
            assert business(api, "GET", f"/api/v1/orders/{target}")["status"] == (
                "PENDING" if operation == "cancel" else "PAID"
            )
            denied = assistant.post(
                f"/agent/v1/confirmations/{identifier}/execute",
                json={"approved": True},
                headers={"Authorization": f"Bearer {tokens[1]}"},
            )
            assert denied.status_code == 404
            invalid = assistant.post(f"/agent/v1/confirmations/{identifier}/execute", json={})
            assert invalid.status_code == 422
            pending = business(assistant, "GET", f"/agent/v1/confirmations/{identifier}")
            assert pending["state"] == "PENDING"
            result = business(
                assistant,
                "POST",
                f"/agent/v1/confirmations/{identifier}/execute",
                json={"approved": True},
            )
            assert result["state"] == "SUCCEEDED"
            expected = "CANCELLED" if operation == "cancel" else "REFUNDED"
            actual = business(api, "GET", f"/api/v1/orders/{target}")
            assert actual["status"] == expected
            if operation == "refund":
                assert actual["refund"]["amountFen"] == result["amountFen"] == 4321
            repeated = business(
                assistant,
                "POST",
                f"/agent/v1/confirmations/{identifier}/execute",
                json={"approved": True},
            )
            assert repeated["result"] == result["result"]
            report["checks"].append(
                {"name": f"explicit_{operation}_ownership_and_duplicate", "passed": True}
            )
        inventory = business(api, "GET", f"/api/v1/sessions/{session['id']}/tiers")
        assert inventory["items"][0]["available"] == 3
        report["checks"].append({"name": "refund_inventory_returned_once", "passed": True})
    finally:
        current = business(api, "GET", f"/api/v1/orders/{paid_order}")
        if current["status"] == "PAID":
            business(
                api,
                "POST",
                f"/api/v1/orders/{paid_order}/refunds",
                json={},
                headers={"Idempotency-Key": "c_cleanup_" + run_id},
            )
    if faults:
        from live_faults import run_faults
        from live_smoke import wait_for

        report["faultMatrix"] = run_faults(
            api, business, wait_for, run_id, tier, session["id"], tokens[0]
        )
    return report
