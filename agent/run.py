"""CMD-compatible launcher; no PowerShell installation required."""

import argparse
import os

import uvicorn


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=["disabled", "demo", "gateway"], default="disabled")
    args = parser.parse_args()
    os.environ["TF_AGENT_MODEL_MODE"] = args.mode
    uvicorn.run(
        "ticketflow_agent.app:create_app",
        factory=True,
        host="127.0.0.1",
        port=8090,
        access_log=False,
    )


if __name__ == "__main__":
    main()
