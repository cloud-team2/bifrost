from app.agents.retrieval import _trace_identifiers


class _R:
    def __init__(self, raw_payload):
        self.raw_payload = raw_payload


def test_extracts_trace_and_pipeline_id_from_raw_payload():
    r = _R({"operation": "query_traces", "result": {"traceId": "abc123", "pipelineId": "p1", "spans": []}})
    assert _trace_identifiers(r) == {"trace_id": "abc123", "pipeline_id": "p1"}


def test_missing_or_non_trace_yields_empty():
    assert _trace_identifiers(_R(None)) == {}
    assert _trace_identifiers(_R({"result": {"connector": "c"}})) == {}
    assert _trace_identifiers(_R({"result": {"traceId": None, "pipelineId": "p1"}})) == {"pipeline_id": "p1"}
