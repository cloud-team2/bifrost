"""Regression: change_ticket.change_window column (was window, renamed for #360)."""
from __future__ import annotations

from app.persistence.change_ticket_repository import ChangeTicket


def test_change_ticket_has_change_window_field() -> None:
    """ChangeTicket model exposes the reserved-keyword-safe change_window field."""
    assert "change_window" in ChangeTicket.model_fields


def test_change_ticket_no_window_field() -> None:
    """The old window field must not be persisted as a model field."""
    assert "window" not in ChangeTicket.model_fields
