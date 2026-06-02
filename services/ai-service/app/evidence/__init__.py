"""Evidence 메타데이터 계층 (스캐폴드).

State에는 원문 inline 금지 — evidence_id·store_ref·summary·redaction_status만 유지한다.
raw evidence는 Evidence Store(별도)에 두고 reference만 다룬다 (design fastapi/README.md).
"""
