package com.bifrost.ops.database.cdc;

import com.bifrost.ops.global.common.datasource.DbType;
import com.bifrost.ops.global.common.error.ApiException;
import com.bifrost.ops.global.common.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 엔진 → {@link CdcReadinessChecker} 매핑(database-registry.md §4.1·§5). 등록된 모든 checker 빈을
 * 주입받아 엔진별로 색인한다. 새 엔진은 checker 빈만 추가하면 자동 등록된다.
 */
@Component
public class CdcReadinessCheckerRegistry {

    private final Map<DbType, CdcReadinessChecker> byEngine;

    public CdcReadinessCheckerRegistry(List<CdcReadinessChecker> checkers) {
        this.byEngine = checkers.stream()
                .collect(Collectors.toMap(CdcReadinessChecker::engine, Function.identity()));
    }

    public CdcReadinessChecker forEngine(DbType engine) {
        CdcReadinessChecker checker = byEngine.get(engine);
        if (checker == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "CDC 점검 미지원 engine: " + engine);
        }
        return checker;
    }
}
