package com.platform.core.inspector;

import com.platform.core.inspector.mariadb.MariaDBInspector;
import com.platform.core.inspector.postgres.PostgresInspector;
import com.platform.core.persistence.entity.DatasourceEntity;
import org.springframework.stereotype.Component;

/**
 * Datasource에 맞는 Inspector 인스턴스 생성.
 * 사용 시 try-with-resources로 자동 닫기.
 */
@Component
public class DatabaseInspectorFactory {

    // TODO: SecretReader 주입 (K8s Secret에서 password 읽기)

    public DatabaseInspector create(DatasourceEntity datasource, String password) {
        return switch (datasource.getDbType()) {
            case POSTGRESQL -> new PostgresInspector(datasource, password);
            case MARIADB -> new MariaDBInspector(datasource, password);
        };
    }
}
