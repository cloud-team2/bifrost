# Issue 803 Event/Alert Scoping Verification

Date: 2026-06-17

## Positive-control verdict

`get_alerts` and `analyze_event_log` are not blind to alert/event data. The positive control is covered by tests that inject matching incident/event rows and verify the tools return them:

- `listAlertsScopedByPipelineExcludesOtherPipelineIncidents`
- `listAlertsScopedByConnectorExcludesSiblingConnectorIncidents`
- `eventIncidentSummaryScopedByPipelineUsesPipelineEventQueryAndFiltersIncidents`
- `eventIncidentSummaryScopedByConnectorUsesConnectorEventQuery`
- `EventRepositoryTest` pipeline and connector scoped query tests

This means an alerts/events count of 0 is healthy when the requested pipeline/connector has no matching incident or warn+ event in the requested window.

## Bug found

The prior implementation queried incident/event rows at project scope, and `analyze_event_log` used the default 2h window without a pipeline or connector target. That allowed old or unrelated pipeline evidence to contaminate an idle pipeline diagnosis.

Fix: `list_alerts` and `analyze_event_log` now accept `pipeline_id` and `connector_name` scope parameters. Backend repository queries filter by pipeline/connector incident grouping/source evidence, and connector-scoped event summaries only return events tied to matching incident IDs or connector/consumer-group tokens.

## Live check

No live resources were mutated. A live positive-control injection was not run because the task constraints prohibit modifying existing live resources, and this workspace does not provide a disposable live test resource in the repo context. Verification is therefore limited to deterministic backend repository/controller tests plus ai-service tool-contract tests.

## Validation

- `./gradlew :services:operations-backend:test --tests com.bifrost.ops.internalops.controller.InternalOpsObservabilityControllerTest --tests com.bifrost.ops.event.persistence.repository.EventRepositoryTest`
- `./gradlew :services:operations-backend:test`
- `.venv/bin/pytest tests/test_tools_registry.py tests/test_planner_pipeline_routing.py`
