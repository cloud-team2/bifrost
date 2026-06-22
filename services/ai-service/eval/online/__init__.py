"""#981 live balanced eval harness for the deployed RCA agent.

Modules:
- live_fault_specs: the fault catalog (inject/recover steps, expected root causes, safety).
- live_eval: the runner (dry-run default, guarded --live) that scores AC@k/Avg@5/ECE.
"""
