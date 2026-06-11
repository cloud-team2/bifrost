import json
import os
import re
import sys
import time
from typing import Any
from urllib.parse import quote

import requests


NOTION_API_BASE = "https://api.notion.com/v1"
NOTION_VERSION = "2022-06-28"

TITLE_PROPERTY = "제목"
NUMBER_PROPERTY = "번호"
WORK_TYPE_PROPERTY = "타입"
STATUS_PROPERTY = "상태"
AUTHOR_PROPERTY = "작성자"
MERGED_AT_PROPERTY = "머지일"
LINK_PROPERTY = "링크"

SUPPORTED_TEXT_TYPES = {"rich_text", "title"}
SUPPORTED_WORK_TYPE_TYPES = {"select", "rich_text", "title"}
SUPPORTED_STATUS_TYPES = {"select", "status", "rich_text", "title"}
SUPPORTED_LINK_TYPES = {"url", "rich_text", "title"}


class NotionSyncError(Exception):
    pass


def eprint(message: str) -> None:
    print(message, file=sys.stderr)


def required_env(name: str) -> str:
    value = os.getenv(name, "").strip()
    if not value:
        raise NotionSyncError(f"{name} is required but is not configured.")
    return value


def notion_request(method: str, path: str, token: str, payload: dict[str, Any] | None = None) -> dict[str, Any]:
    for attempt in range(2):
        response = requests.request(
            method,
            f"{NOTION_API_BASE}{path}",
            headers={
                "Authorization": f"Bearer {token}",
                "Content-Type": "application/json",
                "Notion-Version": NOTION_VERSION,
            },
            json=payload,
            timeout=20,
        )
        if response.status_code not in (429, 529):
            break

        retry_after = response.headers.get("Retry-After", "")
        if attempt == 0:
            delay = retry_delay_seconds(retry_after)
            eprint(f"Notion API limited the request with HTTP {response.status_code}; retrying in {delay}s.")
            time.sleep(delay)
            continue
        raise NotionSyncError(
            f"Notion API limited the request with HTTP {response.status_code}. Retry-After: {retry_after or 'unknown'}"
        )

    if response.status_code >= 400:
        body = response.text[:1000]
        raise NotionSyncError(f"Notion API {method} {path} failed with HTTP {response.status_code}: {body}")
    if not response.content:
        return {}
    return response.json()


def retry_delay_seconds(retry_after: str) -> int:
    try:
        return min(max(int(retry_after), 1), 30)
    except ValueError:
        return 1


def load_event() -> tuple[str, dict[str, Any]]:
    event_name = os.getenv("GITHUB_EVENT_NAME", "").strip()
    event_path = os.getenv("GITHUB_EVENT_PATH", "").strip()
    if not event_name:
        raise NotionSyncError("GITHUB_EVENT_NAME is required but is not configured.")
    if not event_path:
        raise NotionSyncError("GITHUB_EVENT_PATH is required but is not configured.")

    with open(event_path, encoding="utf-8") as event_file:
        return event_name, json.load(event_file)


def parse_work_type(title: str) -> str:
    match = re.match(r"\s*\[([A-Za-z][A-Za-z0-9_-]*)\]", title)
    if not match:
        match = re.match(r"\s*([A-Za-z][A-Za-z0-9_-]*)(?:\([^)]+\))?!?:", title)
    if not match:
        return "other"
    return match.group(1).lower()


def github_record(event_name: str, payload: dict[str, Any]) -> dict[str, Any]:
    if event_name == "pull_request":
        item = payload.get("pull_request")
        if not item:
            raise NotionSyncError("pull_request event payload does not contain pull_request.")
        merged_at = item.get("merged_at")
        state = item.get("state")
        status = "merged" if merged_at else ("open" if state == "open" else "closed")
    elif event_name == "issues":
        item = payload.get("issue")
        if not item:
            raise NotionSyncError("issues event payload does not contain issue.")
        merged_at = None
        status = "open" if item.get("state") == "open" else "closed"
    else:
        raise NotionSyncError(f"Unsupported GitHub event: {event_name}")

    title = item.get("title") or ""
    user = item.get("user") or {}
    return {
        "title": title,
        "number": item.get("number"),
        "work_type": parse_work_type(title),
        "status": status,
        "author": user.get("login") or "",
        "merged_at": merged_at if status == "merged" else None,
        "url": item.get("html_url") or "",
    }


def property_type(schema: dict[str, Any], name: str) -> str:
    prop = schema.get(name)
    if not prop:
        raise NotionSyncError(f"Notion database is missing required property: {name}")
    return prop.get("type", "")


def validate_schema(schema: dict[str, Any]) -> None:
    expected = {
        TITLE_PROPERTY: {"title"},
        NUMBER_PROPERTY: {"number"},
        WORK_TYPE_PROPERTY: SUPPORTED_WORK_TYPE_TYPES,
        STATUS_PROPERTY: SUPPORTED_STATUS_TYPES,
        AUTHOR_PROPERTY: SUPPORTED_TEXT_TYPES | {"select"},
        MERGED_AT_PROPERTY: {"date"},
        LINK_PROPERTY: SUPPORTED_LINK_TYPES,
    }
    for name, supported_types in expected.items():
        actual = property_type(schema, name)
        if actual not in supported_types:
            expected_text = ", ".join(sorted(supported_types))
            raise NotionSyncError(f"Notion property {name} must be one of [{expected_text}], but is {actual}.")


def text_value(kind: str, value: str) -> dict[str, Any]:
    content = value or ""
    if kind == "title":
        return {"title": [{"text": {"content": content}}]}
    if kind == "rich_text":
        return {"rich_text": [{"text": {"content": content}}]}
    raise NotionSyncError(f"Unsupported text property type: {kind}")


def option_value(kind: str, value: str) -> dict[str, Any]:
    name = value or "other"
    if kind == "select":
        return {"select": {"name": name}}
    if kind == "status":
        return {"status": {"name": name}}
    return text_value(kind, name)


def link_value(kind: str, value: str) -> dict[str, Any]:
    if kind == "url":
        return {"url": value or None}
    return text_value(kind, value)


def notion_properties(schema: dict[str, Any], record: dict[str, Any]) -> dict[str, Any]:
    return {
        TITLE_PROPERTY: text_value(property_type(schema, TITLE_PROPERTY), record["title"]),
        NUMBER_PROPERTY: {"number": record["number"]},
        WORK_TYPE_PROPERTY: option_value(property_type(schema, WORK_TYPE_PROPERTY), record["work_type"]),
        STATUS_PROPERTY: option_value(property_type(schema, STATUS_PROPERTY), record["status"]),
        AUTHOR_PROPERTY: option_value(property_type(schema, AUTHOR_PROPERTY), record["author"]),
        MERGED_AT_PROPERTY: {"date": {"start": record["merged_at"]} if record["merged_at"] else None},
        LINK_PROPERTY: link_value(property_type(schema, LINK_PROPERTY), record["url"]),
    }


def find_existing_page(token: str, database_id: str, number: int) -> str | None:
    result = notion_request(
        "POST",
        f"/databases/{quote(database_id, safe='')}/query",
        token,
        {
            "filter": {"property": NUMBER_PROPERTY, "number": {"equals": number}},
            "page_size": 2,
        },
    )
    pages = result.get("results", [])
    if len(pages) > 1 or result.get("has_more"):
        eprint(f"Warning: multiple Notion rows already exist for GitHub number {number}; updating the first row.")
    if not pages:
        return None
    page_id = pages[0].get("id")
    if not page_id:
        raise NotionSyncError(f"Notion query returned a row without an id for GitHub number {number}.")
    return page_id


def upsert_page(token: str, database_id: str, record: dict[str, Any]) -> None:
    if not isinstance(record["number"], int):
        raise NotionSyncError("GitHub event number is missing or invalid.")

    database = notion_request("GET", f"/databases/{quote(database_id, safe='')}", token)
    schema = database.get("properties", {})
    validate_schema(schema)

    properties = notion_properties(schema, record)
    page_id = find_existing_page(token, database_id, record["number"])
    if page_id:
        notion_request("PATCH", f"/pages/{quote(page_id, safe='')}", token, {"properties": properties})
        print(f"Updated Notion row for GitHub number {record['number']}.")
        return

    notion_request(
        "POST",
        "/pages",
        token,
        {"parent": {"database_id": database_id}, "properties": properties},
    )
    print(f"Created Notion row for GitHub number {record['number']}.")


def main() -> int:
    try:
        token = required_env("NOTION_TOKEN")
        database_id = required_env("NOTION_DB_ID")
        event_name, payload = load_event()
        record = github_record(event_name, payload)
        upsert_page(token, database_id, record)
        return 0
    except (OSError, json.JSONDecodeError, requests.RequestException, NotionSyncError) as exc:
        eprint(f"Notion sync failed: {exc}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
