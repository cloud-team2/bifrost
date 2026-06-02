"""Agent 워크플로 계층 (design fastapi/README.md, DETAILS.md §15 Workflow Control).

구성:
- Supervisor: workflow 제어(분기·retry·승인 게이트·루프 가드)
- State: namespace·patch 기반 실행 상태 (evidence는 reference만)
- agents/: 8 LLM agent (Router, Planner, Retrieval, Classifier, RCA, Remediation, Verifier, Report)
- deterministic/: 결정론적 단계 (Correlation Engine, Policy Guard, Approval/Change Gate, Executor)

아직 스캐폴드다. 실제 워크플로/State는 후속 이슈에서 구현한다.
"""
