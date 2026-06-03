package com.bifrost.ops.database.cdc;

import com.bifrost.ops.database.dto.CdcReadinessResponse.CdcCheck;
import com.bifrost.ops.global.common.datasource.DbType;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * 엔진별 CDC 준비도 점검(database-registry.md §4.1). 연결 관리는 호출부(서비스)가 맡고,
 * 구현체는 열린 {@link Connection}에 질의해 항목별 결과만 모은다(순수 질의 로직 → 테스트 용이).
 *
 * <p>새 엔진 추가 시 이 인터페이스를 구현하고 {@code @Component}로 등록하면
 * {@link CdcReadinessCheckerRegistry}가 자동으로 엔진→구현체를 매핑한다(§5).
 */
public interface CdcReadinessChecker {

    DbType engine();

    List<CdcCheck> check(Connection conn) throws SQLException;
}
