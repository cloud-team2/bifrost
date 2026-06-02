"""8 LLM agent (evidence 기반 판단·생성).

Router · Planner · Retrieval · Classifier · RCA · Remediation · Verifier · Report
각 agent는 catalog 밖을 만들지 않으며, 불충분하면 UNKNOWN_WITH_EVIDENCE_GAP으로 수렴한다.
(스캐폴드 — 구현 예정)
"""
