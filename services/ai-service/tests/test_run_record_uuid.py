"""RunRecord가 DB(uuid 컬럼)에서 온 UUID 객체를 str로 코어션하는지 검증.

asyncpg는 uuid 컬럼(agent_run.project_id 등)을 UUID 객체로 반환한다. pydantic
str 필드는 이를 거부하므로, RunRecord에 UUID->str 코어션이 있어야 run 생성/조회가
500 없이 동작한다. (pytest는 asyncpg를 직접 타지 않아 실DB에서만 발현했던 회귀)
"""
from uuid import UUID

from app.persistence.run_repository import RunRecord


def test_run_record_coerces_uuid_project_id_to_str():
    rec = RunRecord(
        run_id="run_x",
        project_id=UUID("9921b39f-864b-4e2c-abfb-ecd1392f08e4"),
        mode="chat",
        status="running",
        catalog_version="v1",
    )
    assert isinstance(rec.project_id, str)
    assert rec.project_id == "9921b39f-864b-4e2c-abfb-ecd1392f08e4"


def test_run_record_coerces_uuid_incident_and_requester():
    rec = RunRecord(
        run_id="run_y",
        project_id="9921b39f-864b-4e2c-abfb-ecd1392f08e4",
        requested_by=UUID("0bde89bc-4715-4b42-8ab0-61bb1fdcc235"),
        incident_id=UUID("11111111-1111-1111-1111-111111111111"),
        mode="chat",
        status="running",
        catalog_version="v1",
    )
    assert isinstance(rec.requested_by, str)
    assert isinstance(rec.incident_id, str)
