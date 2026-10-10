"""Small reviewed corpus. No generated answers, arbitrary documents or remote retrieval."""

import copy
import hashlib
import re
import unicodedata
from datetime import UTC, datetime
from pathlib import Path
from typing import Literal

from pydantic import Field, ValidationError

from .errors import AgentError
from .tools import RuleSearch, StrictInput

ROOT = Path(__file__).resolve().parents[2]
HASH = r"^[a-f0-9]{64}$"


def digest(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


class Source(StrictInput):
    path: Literal["docs/01_需求分析.md", "docs/api/README.md"]
    section: str = Field(min_length=4, max_length=100)
    anchor: str = Field(pattern=r"^[\w-]+$", max_length=120)
    excerpt: str = Field(min_length=10, max_length=2500)
    excerptSha256: str = Field(pattern=HASH)
    documentSha256: str = Field(pattern=HASH)


class Rule(StrictInput):
    ruleId: str = Field(pattern=r"^(BR-[0-9]{3}|TF-RULE-[A-Z]+)$")
    version: str = Field(pattern=r"^[0-9]+\.[0-9]+\.[0-9]+$")
    title: str = Field(min_length=1, max_length=80)
    answer: str = Field(min_length=10, max_length=1200)
    answerSha256: str = Field(pattern=HASH)
    terms: list[list[str]] = Field(min_length=1, max_length=15)
    sources: list[Source] = Field(min_length=1, max_length=3)


class Corpus(StrictInput):
    version: str = Field(pattern=r"^[0-9]+\.[0-9]+\.[0-9]+$")
    reviewedAt: str = Field(pattern=r"^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
    scope: Literal["TicketFlow模拟项目"]
    rules: list[Rule] = Field(min_length=1, max_length=20)


def section_text(document, heading):
    lines = document.splitlines(keepends=True)
    for i, line in enumerate(lines):
        if line.strip() != heading:
            continue
        level = len(heading) - len(heading.lstrip("#"))
        if not level:
            break
        end = len(lines)
        for j in range(i + 1, len(lines)):
            match = re.match(r"^(#{1,6}) ", lines[j])
            if match and len(match[1]) <= level:
                end = j
                break
        return "".join(lines[i:end])
    raise ValueError("source section missing")


class RuleStore:
    def __init__(self, root=ROOT):
        self.root = Path(root).resolve()
        self.path = self.root / "agent/knowledge/rules.v1.json"
        self.corpus = None
        self.fingerprint = ""
        self.sources = {}
        # Invalid knowledge disables only this tool; Java query endpoints remain available.
        try:
            self._load()
        except (OSError, ValueError, ValidationError, TypeError, KeyError):
            self.corpus = None

    def _load(self):
        raw = self.path.read_text("utf-8")
        if len(raw.encode("utf-8")) > 65536:
            raise ValueError("corpus too large")
        self.corpus = Corpus.model_validate_json(raw)
        self.fingerprint = digest(raw)
        ids = [rule.ruleId for rule in self.corpus.rules]
        if len(ids) != len(set(ids)):
            raise ValueError("conflicting rule versions")
        for rule in self.corpus.rules:
            if rule.version != self.corpus.version or digest(rule.answer) != rule.answerSha256:
                raise ValueError("unreviewed answer or version")
            if any(
                not 1 <= len(group) <= 4 or any(not 1 <= len(t) <= 40 for t in group)
                for group in rule.terms
            ):
                raise ValueError("invalid retrieval terms")
            resolved_sources = []
            for source in rule.sources:
                path = (self.root / source.path).resolve()
                if not path.is_relative_to(self.root):
                    raise ValueError("source escapes repository")
                document = path.read_text("utf-8")
                anchor = re.sub(
                    r"\s+", "-", re.sub(r"[^\w\s-]", "", source.section.strip("# ").lower())
                )
                if (
                    digest(document) != source.documentSha256
                    or digest(source.excerpt) != source.excerptSha256
                    or source.anchor != anchor
                    or source.excerpt not in section_text(document, source.section)
                ):
                    raise ValueError("source drift or conflict")
                ref = source.model_dump()
                ref["line"] = document[: document.index(source.excerpt)].count("\n") + 1
                resolved_sources.append(ref)
            self.sources[rule.ruleId] = resolved_sources

    def _verify_snapshot(self):
        try:
            if self.corpus is None or digest(self.path.read_text("utf-8")) != self.fingerprint:
                raise ValueError("corpus changed")
            checked = set()
            for rule in self.corpus.rules:
                for source in rule.sources:
                    if source.path in checked:
                        continue
                    checked.add(source.path)
                    current_hash = digest((self.root / source.path).read_text("utf-8"))
                    if current_hash != source.documentSha256:
                        raise ValueError("source changed")
        except (OSError, ValueError):
            raise AgentError(
                "RULES_UNAVAILABLE", 503, "规则资料未通过校验，请重新审核版本与出处"
            ) from None

    def search(self, arguments):
        values = RuleSearch.model_validate(arguments)
        self._verify_snapshot()
        text = re.sub(r"\s+", "", unicodedata.normalize("NFKC", values.query)).lower()
        status = "MATCHED"
        notice = "仅解释 TicketFlow 模拟项目规则；具体订单与实时状态须查询本人订单，由 Java 判断。"
        if any(
            term in text
            for term in (
                "直接退款",
                "帮我退款",
                "给我退款",
                "替我退款",
                "执行退款",
                "立即退款",
                "确认退款",
                "直接支付",
                "执行支付",
                "帮我支付",
                "帮我下单",
                "帮我取消",
                "执行取消",
                "替我取消",
                "管理员接口",
                "伪造出处",
                "伪造引用",
                "修改规则文件",
            )
        ):
            status, notice = "ACTION_REQUIRED", "当前只提供规则说明和查询，无法执行交易或修改规则。"
        elif any(
            term in text
            for term in (
                "到账",
                "原路",
                "工作日",
                "改签",
                "实名",
                "身份证",
                "学生票",
                "优惠券",
                "保险",
                "手续费阶梯",
                "大麦",
                "猫眼",
                "真实演出",
                "发票",
                "法律",
            )
        ):
            status = "INSUFFICIENT"
            notice = "审核资料未覆盖这项政策，无法作出规则或交易承诺。"
        elif re.search(r"(?:这场|这个活动|活动\d+|场次\d+).*(?:价格|库存|开售时间|几点开售)", text):
            status = "NEEDS_LIVE_DATA"
            notice = "价格、库存和具体开售时间需要查询活动或场次，不能从规则推断。"
        ranked = []
        if status == "MATCHED":
            for rule in self.corpus.rules:
                score = sum(
                    sum(len(term) for term in group)
                    for group in rule.terms
                    if all(term in text for term in group)
                )
                if score:
                    ranked.append((score, rule))
            ranked.sort(key=lambda item: (-item[0], item[1].ruleId))
            if not ranked:
                status, notice = "INSUFFICIENT", "没有足够的审核规则依据，请明确要了解的票务规则。"
            elif re.search(r"这笔|这张票|我的订单|我这单|订单[0-9]+", text):
                status = "NEEDS_ORDER_QUERY"
                notice = "以下是通用规则，不能认定这笔订单可操作；需查询本人订单和成交快照。"
        items = [
            {
                "ruleId": rule.ruleId,
                "version": rule.version,
                "title": rule.title,
                "text": rule.answer,
                "answerSha256": rule.answerSha256,
                "sources": copy.deepcopy(self.sources[rule.ruleId]),
            }
            for _, rule in ranked[: values.limit]
        ]
        return {
            "tool": "search_rules",
            "data": {
                "corpusVersion": self.corpus.version,
                "scope": self.corpus.scope,
                "status": status,
                "items": items,
                "notice": notice,
            },
            "source": {
                "method": "LOCAL",
                "path": "agent/knowledge/rules.v1.json",
                "version": self.corpus.version,
                "corpusSha256": self.fingerprint,
                "queriedAt": datetime.now(UTC).isoformat(),
            },
        }


def render_rules(cards):
    lines, citations, seen = [], [], set()
    for card in cards:
        if card["tool"] != "search_rules":
            continue
        data = card["data"]
        lines.append(data["notice"])
        for item in data["items"]:
            key = (item["ruleId"], item["version"])
            if key in seen:
                continue
            seen.add(key)
            lines.append(f"【{item['ruleId']} v{item['version']}】{item['text']}")
            for i, source in enumerate(item["sources"]):
                citations.append(
                    {
                        "citationId": f"{item['ruleId']}@{item['version']}:{i}",
                        "ruleId": item["ruleId"],
                        "version": item["version"],
                        **source,
                    }
                )
    return "\n".join(dict.fromkeys(lines)), citations
