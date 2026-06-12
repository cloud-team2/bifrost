-- #558: 인시던트 심각도를 스펙 부록 B.7에 맞춰 WARN/ERROR → WARNING/CRITICAL로 정합.
-- 상태(status)는 OPEN/RESOLVED 유지 + INVESTIGATING 신규(데이터 변환 불필요).

UPDATE incidents SET severity = 'WARNING'  WHERE severity = 'WARN';
UPDATE incidents SET severity = 'CRITICAL' WHERE severity = 'ERROR';
