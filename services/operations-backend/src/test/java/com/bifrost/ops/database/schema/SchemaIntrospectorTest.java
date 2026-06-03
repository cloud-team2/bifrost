package com.bifrost.ops.database.schema;

import com.bifrost.ops.database.dto.DatabaseSchemaResponse;
import com.bifrost.ops.database.dto.DatabaseSchemaResponse.ColumnSchema;
import com.bifrost.ops.database.dto.DatabaseSchemaResponse.TableSchema;
import com.bifrost.ops.database.persistence.entity.DatasourceEntity;
import com.bifrost.ops.global.common.datasource.DbType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SchemaIntrospector 통합 테스트(#28). 실제 PostgreSQL에 테이블을 만들고 JDBC 메타데이터로
 * 컬럼·타입·nullable·pk·index를 읽는지 검증한다.
 *
 * <p>Docker가 없으면 전체 skip({@code disabledWithoutDocker}). CI에서 실행된다.
 */
@Testcontainers(disabledWithoutDocker = true)
class SchemaIntrospectorTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll
    static void seed() throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TABLE orders (
                        id BIGINT PRIMARY KEY,
                        customer VARCHAR(100) NOT NULL,
                        note TEXT
                    )""");
            st.execute("CREATE INDEX idx_orders_customer ON orders(customer)");
        }
    }

    @Test
    void readsColumnsPrimaryKeyAndIndex() {
        DatasourceEntity ds = new DatasourceEntity();
        ds.setDbType(DbType.POSTGRESQL);
        ds.setHost(POSTGRES.getHost());
        ds.setPort(POSTGRES.getFirstMappedPort());
        ds.setDbName(POSTGRES.getDatabaseName());
        ds.setUsername(POSTGRES.getUsername());

        DatabaseSchemaResponse schema = new SchemaIntrospector().introspect(ds, POSTGRES.getPassword());

        Optional<TableSchema> orders = schema.tables().stream()
                .filter(t -> t.name().equals("orders")).findFirst();
        assertThat(orders).isPresent();

        ColumnSchema id = column(orders.get(), "id");
        assertThat(id.primaryKey()).isTrue();
        assertThat(id.indexed()).isTrue();      // PK는 인덱스
        assertThat(id.nullable()).isFalse();

        ColumnSchema customer = column(orders.get(), "customer");
        assertThat(customer.nullable()).isFalse();
        assertThat(customer.indexed()).isTrue();   // idx_orders_customer
        assertThat(customer.primaryKey()).isFalse();

        ColumnSchema note = column(orders.get(), "note");
        assertThat(note.nullable()).isTrue();
        assertThat(note.indexed()).isFalse();
    }

    private static ColumnSchema column(TableSchema t, String name) {
        return t.columns().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }
}
