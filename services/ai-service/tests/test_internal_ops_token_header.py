"""(#646) /internal/ops service-identity 토큰 헤더 동봉 검증.

ops-backend SecurityConfig 의 X-Internal-Token 게이트와 짝. 토큰이 설정되면 모든 ops 호출
(health·preapproved·일반 operation)이 거치는 _client() 기본 헤더에 동봉되고, 비면 동봉하지 않아
게이트 비활성 환경과 호환된다.
"""
from __future__ import annotations

from app.tools import spring_client
from app.tools.spring_client import INTERNAL_OPS_TOKEN_HEADER, SpringOpsClient


def test_client_includes_internal_token_header_when_set(monkeypatch):
    monkeypatch.setattr(spring_client.settings, "internal_ops_token", "s3cret")
    client = SpringOpsClient()._client()
    assert client.headers.get(INTERNAL_OPS_TOKEN_HEADER) == "s3cret"


def test_client_omits_internal_token_header_when_unset(monkeypatch):
    monkeypatch.setattr(spring_client.settings, "internal_ops_token", "")
    client = SpringOpsClient()._client()
    assert INTERNAL_OPS_TOKEN_HEADER.lower() not in {k.lower() for k in client.headers}
