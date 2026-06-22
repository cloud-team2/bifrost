"""Evidence metadata hygiene helpers shared by classifier and RCA paths."""
from __future__ import annotations

import re

CONTROL_METADATA_KEYS = {
    "accepted",
    "accepted_root_cause_id",
    "answer",
    "answer_phrase",
    "case_id",
    "corrected_root_cause_id",
    "expected",
    "expected_root_cause",
    "expected_root_cause_id",
    "gold",
    "label",
    "oracle",
    "prediction",
    "predicted_root_cause_id",
    "root_cause",
    "root_cause_id",
}

CONTROL_ID_RE = re.compile(r"^[A-Z0-9]+(?:_[A-Z0-9]+){1,}$")
CONTROL_METADATA_RE = re.compile(
    r"\b(?:answer(?:_phrase)?|case[_ -]?id|expected(?:_root[_ -]?cause(?:_id)?)?|"
    r"accepted[_ -]?root[_ -]?cause(?:[_ -]?id)?|corrected[_ -]?root[_ -]?cause(?:[_ -]?id)?|"
    r"predicted[_ -]?root[_ -]?cause(?:[_ -]?id)?|gold|label|oracle|prediction|"
    r"root[_ -]?cause(?:[_ -]?id)?)\b"
    r"\s*(?:[:=]|is|는|은)?\s*[0-9A-Za-z_.-]+",
    re.IGNORECASE,
)


def strip_control_metadata(value: str) -> str:
    """Remove benchmark/gold/control labels before matching or prompting."""
    return CONTROL_METADATA_RE.sub(" ", value)


def is_control_metadata_key(key: str) -> bool:
    normalized = key.strip().casefold()
    return normalized in CONTROL_METADATA_KEYS or "root_cause" in normalized


def is_control_id(value: str) -> bool:
    return bool(CONTROL_ID_RE.fullmatch(value.strip()))
