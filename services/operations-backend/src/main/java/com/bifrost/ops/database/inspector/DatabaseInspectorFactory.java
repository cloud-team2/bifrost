package com.bifrost.ops.database.inspector;

import com.bifrost.ops.database.connection.DynamicDataSourceFactory;
import com.bifrost.ops.database.inspector.mariadb.MariaDBInspector;
import com.bifrost.ops.database.inspector.postgres.PostgresInspector;
import com.bifrost.ops.database.persistence.entity.DatasourceEntity;
import org.springframework.stereotype.Component;

/**
 * Datasource에 맞는 Inspector 인스턴스 생성. try-with-resources로 자동 닫는다.
 */
@Component
public class DatabaseInspectorFactory {

    private final DynamicDataSourceFactory dataSourceFactory;

    public DatabaseInspectorFactory(DynamicDataSourceFactory dataSourceFactory) {
        this.dataSourceFactory = dataSourceFactory;
    }

    public DatabaseInspector create(DatasourceEntity datasource, String password) {
        return switch (datasource.getDbType()) {
            case POSTGRESQL -> new PostgresInspector(datasource, password, dataSourceFactory);
            case MARIADB -> new MariaDBInspector(datasource, password, dataSourceFactory);
        };
    }
}
