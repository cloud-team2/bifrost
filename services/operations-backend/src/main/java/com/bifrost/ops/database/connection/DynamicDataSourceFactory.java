package com.bifrost.ops.database.connection;

import com.bifrost.ops.database.persistence.entity.DatasourceEntity;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.stereotype.Component;

/**
 * 등록된 DB에 대해 짧게 쓰고 닫는 동적 HikariCP DataSource를 만든다(스키마 조회·CDC 점검 공유).
 * 자격증명(password)은 호출부가 SecretStore에서 resolve해 넘긴다 — 풀 1·5초 timeout으로 최소화.
 *
 * <p>반드시 try-with-resources로 닫는다. 협력자로 분리해 호출부(introspector·checker 서비스)의
 * 단위 테스트에서 목으로 대체할 수 있게 한다.
 */
@Component
public class DynamicDataSourceFactory {

    private static final long TIMEOUT_MS = 5000;

    public HikariDataSource create(DatasourceEntity ds, String password, boolean readOnly) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(JdbcUrls.build(ds.getDbType(), ds.getHost(), ds.getPort(), ds.getDbName()));
        cfg.setUsername(ds.getUsername());
        cfg.setPassword(password);
        cfg.setConnectionTimeout(TIMEOUT_MS);
        cfg.setMaximumPoolSize(1);
        cfg.setInitializationFailTimeout(1);
        cfg.setReadOnly(readOnly);
        cfg.setPoolName("db-dynamic");
        return new HikariDataSource(cfg);
    }
}
