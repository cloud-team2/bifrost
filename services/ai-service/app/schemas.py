"""공통 응답 봉투와 표준 에러 코드 (design fastapi/api.md §3, §4).

성공: {ok: true, request_id, data}
실패: {ok: false, request_id, error: {code, message, retryable}}
"""
from __future__ import annotations

from enum import Enum
from typing import Any, Generic, Optional, TypeVar

from pydantic import BaseModel

T = TypeVar("T")


class ErrorCode(str, Enum):
    VALIDATION_FAILED = "VALIDATION_FAILED"
    UNAUTHORIZED = "UNAUTHORIZED"
    FORBIDDEN = "FORBIDDEN"
    RUN_NOT_FOUND = "RUN_NOT_FOUND"
    INCIDENT_NOT_FOUND = "INCIDENT_NOT_FOUND"
    ACTION_NOT_FOUND = "ACTION_NOT_FOUND"
    APPROVAL_NOT_FOUND = "APPROVAL_NOT_FOUND"
    RUN_ALREADY_CLOSED = "RUN_ALREADY_CLOSED"
    POLICY_DENIED = "POLICY_DENIED"
    SPRING_BACKEND_ERROR = "SPRING_BACKEND_ERROR"
    LLM_PROVIDER_ERROR = "LLM_PROVIDER_ERROR"
    STREAM_UNAVAILABLE = "STREAM_UNAVAILABLE"
    NOT_IMPLEMENTED = "NOT_IMPLEMENTED"


class ErrorBody(BaseModel):
    code: ErrorCode
    message: str
    retryable: bool = False


class ApiResponse(BaseModel, Generic[T]):
    ok: bool
    request_id: str
    data: Optional[T] = None
    error: Optional[ErrorBody] = None

    @classmethod
    def success(cls, request_id: str, data: Any = None) -> "ApiResponse":
        return cls(ok=True, request_id=request_id, data=data)

    @classmethod
    def failure(cls, request_id: str, code: ErrorCode, message: str, retryable: bool = False) -> "ApiResponse":
        return cls(ok=False, request_id=request_id, error=ErrorBody(code=code, message=message, retryable=retryable))
