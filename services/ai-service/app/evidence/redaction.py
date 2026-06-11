"""Redaction helpers for raw evidence payloads.

State and events must not carry raw evidence. Raw payloads persisted to the
Evidence Store are redacted first so hydrate paths can return operational
context without leaking credentials.
"""
from __future__ import annotations

import re
from collections.abc import Mapping, Sequence
from typing import Any

REDACTED = "[REDACTED]"

_SENSITIVE_KEY_PARTS = (
    "authorization",
    "cookie",
    "password",
    "passwd",
    "secret",
    "token",
    "api_key",
    "apikey",
    "access_key",
    "refresh_token",
    "private_key",
    "connection_string",
    "dsn",
    "jdbc_url",
)

_KEY_VALUE_RE = re.compile(
    r"(?i)\b(password|passwd|pwd|token|secret|api[_-]?key|access[_-]?key|"
    r"refresh[_-]?token|authorization)\s*[:=]\s*([^\s,;]+)"
)
_BEARER_RE = re.compile(r"(?i)\bBearer\s+[A-Za-z0-9._~+/\-=]+")
_URI_CREDENTIAL_RE = re.compile(r"(?P<scheme>[A-Za-z][A-Za-z0-9+.-]*://)(?P<creds>[^/@\s]+:[^/@\s]+)@")


def redact_payload(value: Any) -> Any:
    """Return a recursively redacted JSON-compatible value."""
    if isinstance(value, Mapping):
        redacted: dict[str, Any] = {}
        for key, item in value.items():
            key_str = str(key)
            redacted[key_str] = REDACTED if _is_sensitive_key(key_str) else redact_payload(item)
        return redacted

    if isinstance(value, str):
        return redact_text(value)

    if isinstance(value, Sequence) and not isinstance(value, (bytes, bytearray)):
        return [redact_payload(item) for item in value]

    return value


def redact_text(value: str) -> str:
    """Mask common secret forms while preserving useful operational text."""
    value = _BEARER_RE.sub("Bearer " + REDACTED, value)
    value = _KEY_VALUE_RE.sub(lambda match: f"{match.group(1)}={REDACTED}", value)
    return _URI_CREDENTIAL_RE.sub(lambda match: f"{match.group('scheme')}{REDACTED}@", value)


def _is_sensitive_key(key: str) -> bool:
    normalized = key.casefold().replace("-", "_")
    return any(part in normalized for part in _SENSITIVE_KEY_PARTS)
