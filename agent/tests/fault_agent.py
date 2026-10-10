"""Test-only child: hard exit after Java receipt, before local persistence."""

import argparse
import os
import sys
from dataclasses import replace
from pathlib import Path

import uvicorn

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from ticketflow_agent.app import create_app  # noqa: E402
from ticketflow_agent.config import Settings  # noqa: E402
from ticketflow_agent.confirmations import ConfirmationStore  # noqa: E402


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--exit-before-finish", action="store_true")
    args = parser.parse_args()
    if args.exit_before_finish:
        original = ConfirmationStore.finish

        def finish(self, row, state, result=None):
            if state == "SUCCEEDED":
                os._exit(77)
            return original(self, row, state, result)

        ConfirmationStore.finish = finish
    settings = replace(Settings.from_env(), confirmation_ttl=2, confirmation_lease=1)
    uvicorn.run(create_app(settings), host="127.0.0.1", port=args.port, access_log=False)


if __name__ == "__main__":
    main()
