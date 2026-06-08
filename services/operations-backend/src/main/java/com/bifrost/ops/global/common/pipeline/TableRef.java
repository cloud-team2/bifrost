package com.bifrost.ops.global.common.pipeline;

/**
 * 테이블 참조.
 * - PostgreSQL: schema = pg schema (public 등), name = table name
 * - MariaDB: schema = database 이름, name = table name
 */
public record TableRef(
    String schema,
    String name
) {
    public String fullyQualifiedName() {
        return schema + "." + name;
    }
}
