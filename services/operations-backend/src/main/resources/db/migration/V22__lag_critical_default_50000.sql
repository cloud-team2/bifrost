-- #559: lag CRITICAL 임계 기본값을 스펙(부록 B.1/B.4)에 맞춰 20,000 → 50,000으로 교정.
-- (warning 5,000은 일치, CHECK(critical > warning) 유지.)

-- 컬럼 기본값 갱신(신규 워크스페이스).
ALTER TABLE workspace_settings
    ALTER COLUMN lag_critical_threshold SET DEFAULT 50000;

-- 기존 행 중 옛 기본값(20,000)을 그대로 쓰던 워크스페이스만 갱신(사용자가 바꾼 값은 보존).
UPDATE workspace_settings
    SET lag_critical_threshold = 50000, updated_at = now()
    WHERE lag_critical_threshold = 20000;
