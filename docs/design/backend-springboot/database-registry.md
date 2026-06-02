# Spring Boot Operations Backend — Database Registry

> 요약은 [overview.md](./overview.md). 이 파일은 source/sink Database 등록·점검(FR-013 ~ FR-015)을 다룬다.

## 3. Database Registry

### 1. 목적

이 문서는 Spring Boot Operations Backend가 **source/sink Database를 등록하고 점검**하는 방법을 정의한다(FR-013 ~ FR-015). 세 단계로 처리한다.

```text
연결 테스트(동적 DataSource) → 자격증명 secretRef 보관 → CDC 준비도 점검
```

### 2. Step 1 — 연결 테스트 (동적 DataSource)

입력(engine·host·port·dbName·user·password)으로 HikariCP DataSource를 **동적으로** 만들어 `SELECT 1`을 실행한다. 실패 시 예외를 분류해 사유를 반환한다.

```java
HikariConfig config = new HikariConfig();
config.setJdbcUrl(jdbcUrl(engine, host, port, dbName));
config.setUsername(user);
config.setPassword(password);
config.setConnectionTimeout(5000);   // 5초 timeout
config.setMaximumPoolSize(1);        // 테스트용 최소 풀

try (HikariDataSource ds = new HikariDataSource(config)) {
    ds.getConnection().createStatement().execute("SELECT 1");
} catch (Exception e) {
    throw new DbConnectionException(classify(e)); // 연결 거부 / 인증 실패 / DB 없음 / timeout
}
```

`classify(e)`는 예외를 `CONNECTION_REFUSED`, `AUTH_FAILED`, `DB_NOT_FOUND`, `TIMEOUT`, `UNKNOWN` 5종으로 분류한다. **이 집합이 단일 출처**이며 프론트(연결 테스트 실패 안내)와 todo는 이 코드를 그대로 쓴다.

### 3. Step 2 — 자격증명 secretRef 보관

연결 성공 시 password를 **외부 Secret 저장소(K8s Secret 또는 Secrets Manager)** 에 저장하고, `database` 테이블에는 그 **참조(`secret_ref`)만** 저장한다. 자격증명 평문·암호문을 메타DB에 두지 않는다.

- secretRef 추상화: MVP는 local/mock secret store, 이후 K8s Secret/Secrets Manager로 교체(인터페이스 동일).
- API 응답에서 password는 항상 `****`로 마스킹(secretRef도 노출하지 않음).
- Connector CR 생성 시 secretRef가 가리키는 Secret을 참조하거나 그 시점에만 값을 읽어 `spec.config.database.password`에 주입한다.

```text
SecretStore
  put(credential) -> secret_ref          // 등록 시 1회
  resolve(secret_ref) -> credential       // provisioning 시점에만 호출
```

이렇게 하면 자격증명 노출면이 좁아지고(앱 DB에 비밀값 없음) 로테이션·키 관리를 Secret 저장소에 위임할 수 있다.

### 4. Step 3 — CDC 준비도 점검 (FR-015)

DB 등록 직후 자동 실행하며, Database 상세 화면에서 수동 재점검도 가능하다. **DB 엔진별로 구현체를 선택하는 인터페이스 추상화**로 설계한다.

#### 4.1 인터페이스

```java
interface CdcReadinessChecker {
    List<CheckItem> check(DatabaseConfig db);
}

class PostgresCdcChecker implements CdcReadinessChecker { ... }
class MariadbCdcChecker  implements CdcReadinessChecker { ... }

// 엔진 → 구현체 선택 (예: 팩토리/스프링 빈 맵)
CdcReadinessChecker checker = checkerRegistry.forEngine(db.getEngine());
```

각 구현체는 동적 DataSource로 대상 DB에 직접 질의해 항목별 결과를 모은다.

#### 4.2 결과 스키마

```json
{
  "overallStatus": "BLOCKED",
  "checks": [
    { "name": "WAL Level", "status": "BLOCKED", "actual": "replica", "expected": "logical",
      "hint": "ALTER SYSTEM SET wal_level = logical; SELECT pg_reload_conf();" },
    { "name": "Max WAL Senders", "status": "OK", "actual": "10", "expected": "> 0" }
  ]
}
```

- `status` ∈ `OK` / `WARNING` / `BLOCKED`.
- `overallStatus` = 항목 중 가장 심각한 수준 (`BLOCKED` > `WARNING` > `OK`).
- `hint`가 있으면 "이렇게 설정하면 됩니다" 가이드로 노출.

| overallStatus | 의미 |
| --- | --- |
| `OK` | CDC Source로 사용 준비 완료 |
| `WARNING` | 부분 미흡, 연결은 가능(경고 배지) |
| `BLOCKED` | 파이프라인 Source 선택 불가 |

#### 4.3 PostgreSQL 점검 항목

| 항목 | 쿼리 | OK | BLOCKED |
| --- | --- | --- | --- |
| WAL Level | `SHOW wal_level` | `logical` | 그 외 |
| Max WAL Senders | `SHOW max_wal_senders` | `> 0` | `= 0` |
| Max Replication Slots | `SHOW max_replication_slots` | `> 0` | `= 0` |
| Replication Slot 여유 | `SELECT count(*) FROM pg_replication_slots` | 여유 있음 | 가득 참 → WARNING |
| REPLICATION 권한 | `SELECT rolreplication FROM pg_roles WHERE rolname = current_user` | `true` | `false` |
| Publication (pgoutput) | 기존 publication 존재 또는 `CREATE` 권한으로 생성 가능 | 있음/생성 가능 | 없음·생성 불가 → WARNING |

#### 4.4 MariaDB 점검 항목

| 항목 | 쿼리 | OK | BLOCKED |
| --- | --- | --- | --- |
| Binary Log | `SHOW VARIABLES LIKE 'log_bin'` | `ON` | `OFF` |
| Binlog Format | `SHOW VARIABLES LIKE 'binlog_format'` | `ROW` | 그 외 |
| Binlog Row Image | `SHOW VARIABLES LIKE 'binlog_row_image'` | `FULL` | `MINIMAL` → WARNING |
| Server ID | `SHOW VARIABLES LIKE 'server_id'` | `> 0` | `0` |
| REPLICATION 권한 | `SHOW GRANTS FOR current_user()` 파싱 | `REPLICATION SLAVE` 포함 | 없음 |

#### 4.5 UI 연계

- `BLOCKED` 항목이 있으면 파이프라인 생성 마법사에서 해당 DB를 **Source로 선택 불가**.
- `WARNING`이면 선택 가능하되 경고 배지.
- 각 항목의 `hint`를 함께 노출.

### 5. 새 엔진 추가

엔진을 추가하려면 `CdcReadinessChecker` 구현체와 `checkerRegistry` 등록만 추가하면 된다. 연결 테스트(`jdbcUrl` 빌더)와 데이터 모델(engine enum)도 함께 확장한다.

### 6. API (frontend-facing)

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/api/v1/workspaces/{wsId}/databases` | 등록 DB 목록(engine/q 필터, `role`은 파생값) |
| `POST` | `/api/v1/workspaces/{wsId}/databases/connection-test` | 연결 테스트 |
| `POST` | `/api/v1/workspaces/{wsId}/databases` | 등록(자격증명은 secretRef로 보관) |
| `GET` | `/api/v1/workspaces/{wsId}/databases/{dbId}` | 상세(password 마스킹) |
| `GET` | `/api/v1/workspaces/{wsId}/databases/{dbId}/cdc-readiness` | CDC 준비도 점검 |
| `GET` | `/api/v1/workspaces/{wsId}/databases/{dbId}/schema` | 스키마(FR-016) |
| `GET` | `/api/v1/workspaces/{wsId}/databases/{dbId}/metrics` | 지표(FR-017) |
