# Git 컨벤션

**브랜치 모델**: main / develop 분리
**머지 방식**: Squash and merge
**브랜치 보호**: main, develop 직접 푸시 금지, PR 필수, CI 통과 필수

---

## 1. 브랜치 구조

```
main        ← 배포 가능한 상태만 유지. develop에서 PR로만 머지.
  └─ develop ← 통합 브랜치. 모든 작업 브랜치의 base이자 머지 대상.
       └─ feat/#42     ← 새 기능
       └─ fix/#57      ← 버그 수정
       └─ chore/#10    ← 설정, 의존성 등
       └─ docs/#23     ← 문서만
       └─ refactor/#38 ← 동작 변경 없는 코드 정리
```

- 모든 작업은 **GitHub Issue 먼저 생성** → 브랜치 생성 → PR
- 브랜치는 항상 **develop에서** 생성
- PR 대상도 항상 **develop**
- main ← develop 머지는 Sprint 종료 시 또는 릴리스 시점에만

---

## 2. 브랜치 명명

```
{type}/#{이슈번호}
```

### type

| type | 사용 시점 |
|---|---|
| `feat` | 새 기능 개발 |
| `fix` | 버그 수정 |
| `chore` | 의존성 업데이트, 설정 변경 등 (동작 변경 없음) |
| `docs` | 문서만 변경 |
| `refactor` | 동작 변경 없는 코드 정리 |
| `hotfix` | main에서 직접 긴급 수정 (아래 별도 설명) |

### 예시

```
feat/#12
feat/#34
fix/#57
chore/#10
docs/#23
refactor/#38
```

---

## 3. 커밋 메시지

```
#{이슈번호} [type] 메시지
```

### type

| type | 사용 시점 |
|---|---|
| `feat` | 새 기능 |
| `fix` | 버그 수정 |
| `chore` | 의존성 업데이트, 설정 변경 등 (동작 변경 없음) |
| `docs` | 문서만 변경 |
| `refactor` | 동작 변경 없는 코드 정리 |
| `test` | 테스트 추가/수정 |

### 예시

```
#12 [feat] jwt 기반 인증 기능 추가
#15 [feat] PostgresInspector CDC readiness 검사 구현
#34 [feat] 회원가입 시 테넌트 네임스페이스 자동 프로비저닝
#57 [fix] TenantProvisioner 멱등성 처리 (AlreadyExists 예외 처리)
#3 [chore] Gradle wrapper 8.10 추가
#1 [docs] git 협업 전략 문서 작성
#38 [refactor] Inspector 예외 처리 분리
#44 [test] TenantProvisioner 단위 테스트 추가
```

---

## 4. 이슈 → 브랜치 → PR 흐름

```
1. GitHub에서 Issue 생성 (제목, 담당자, 라벨 지정)
2. develop에서 브랜치 생성
   git checkout develop && git pull origin develop
   git checkout -b feat/#42
3. 작업 + 커밋
   git commit -m "#42 [feat] jwt 기반 인증 기능 추가"
4. develop 최신화 (rebase)
   git fetch origin && git rebase origin/develop
5. 푸시 → PR 생성 (base: develop)
   git push origin feat/#42
6. 리뷰 → Squash and merge → 브랜치 삭제
```

---

## 5. PR 규칙

- **PR 제목**: `#{이슈번호} [type] 메시지` — 커밋 메시지와 동일한 형식
- **PR base 브랜치**: develop (항상)
- **머지**: Squash and merge → 브랜치 자동 삭제

```
예시 PR 제목:
#12 [feat] jwt 기반 인증 기능 추가
#34 [feat] TenantProvisioner fabric8 구현
#57 [fix] TenantProvisioner 멱등성 처리
```

---

