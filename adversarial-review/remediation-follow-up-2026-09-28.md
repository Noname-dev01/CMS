# PR6-R1 / PR4-T1 Remediation Verification

검증 대상: `deploy-check-2026-09-28.md`의 두 finding만. 기준 HEAD: `ec6336cb5ea3aaaa80d94efb88555e9b381da810` + 이번 작업의 테스트/문서 변경.

이 문서는 기존 감사 보고서를 대체하지 않는 후속 기록이다. PR1~5 제품 코드, 설정, dependency, migration 및 `prod-backup.sh`/`prod-restore.sh`는 변경하지 않았다. 기존 roadmap/portfolio 작업도 변경하지 않았다.

## PR6-R1 — 런북 실패 제어 및 원래 앱 상태

### 변경

- 실행 전 daemon/대상 확인, 다른 backup/restore·배포·외부 writer 부재를 전제로 명시했다.
- 원래 실행 상태를 기록하고 앱 정지가 확인된 경우에만 기존 backup 명령을 호출한다. DB는 정지하지 않는다.
- 원래 정지된 앱은 backup 성공/실패와 무관하게 시작하지 않는다.
- 원래 실행 중인 앱은 backup 결과를 보존한 뒤 재개·health 확인한다. `BACKUP`, `BACKUP_EXIT_CODE`, `APP_RECOVERY`를 따로 출력한다.
- backup 실패 + 재개 성공은 non-zero다. backup 성공 + 재개 실패도 non-zero지만 유효 백업을 폐기하지 않는다.
- start/health 실패 후 앱 정지까지 확인한다. 정지 확인 불가·중단은 성공으로 감추지 않고 수동 조치 대상으로 표시한다.
- 파괴적 restore 실패는 앱 정지 유지·안전 백업·수동 재복구라는 별도 계약이다. backup의 재개 규칙을 적용하지 않는다.

### 재검증

`scripts/tests/quiesced-runbook-test.sh`는 deployment 문서의 실제 Bash 블록을 추출해 실행한다. Docker/make/curl/sleep을 테스트 함수로 대체하며 실제 컨테이너·볼륨·백업은 조작하지 않는다. 결과뿐 아니라 명령 순서, 최종 앱 상태, 종료 코드를 단언한다.

| 시나리오 | 확인한 결과 |
|---|---|
| 원래 실행 중 / 정상 backup | stop → backup → start → health, 양쪽 SUCCESS, 0 |
| 원래 정지 / 정상 backup | backup만 실행, 정지 유지, 0 |
| 원래 실행 중 / backup 실패 | 재개 성공이어도 BACKUP=FAILED, 원래 실패 코드 7 유지 |
| 원래 정지 / backup 실패 | start 없음, 실패 코드 7 유지 |
| stop 명령 실패 | backup/start 금지, 수동 상태 확인 |
| stop 성공 코드지만 여전히 실행 중 | backup/start 금지, 정지 미확인 |
| 최초 inspect 실패 / 잘못된 상태 값 | backup/start/stop 없이 실패 |
| stop 이후 inspect 실패 | backup/start 금지, 정지 미확인 |
| start 실패 | backup 성공과 별도 실패, 정지 확인 |
| health timeout | backup 성공과 별도 실패, 정지 확인 |
| recovery 실패 뒤 stop도 실패 | 정지 완료로 오인하지 않음, 수동 조치 |

12개 시나리오 통과. Bash 구문 검사도 통과. 이는 런북 제어 흐름 검증이지 실제 backup/restore drill의 성공 증거가 아니다.

## PR4-T1 — 영구 동시성 시험의 결정성·예외·정리

### 변경

- barrier 기반 same-row 시험을 A-first/B-first 두 시험으로 교체했다.
- 실제 `MenuService`와 Spring transaction을 유지한다. repository proxy에 테스트 전용 advice를 붙여 선행 잠금 조회가 반환된 직후에만 대기한다. 제품 코드에는 latch/probe를 추가하지 않았다.
- 양쪽 JDBC connection ID를 기록하고 별도 관측 연결로 `INNODB_LOCK_WAITS`를 확인한다. 대기 query가 `SELECT FOR UPDATE`인지 확인한 후에만 선행 작업을 해제한다. sleep/Future 미완료를 잠금 증거로 쓰지 않는다.
- 후행 조회가 선행 commit의 이름/useYn을 읽었는지, 두 서비스 Future가 성공했는지, 최종 새 이름 및 `useYn=false`가 유지되는지 단언한다.
- 보조 timeout 시험은 `PessimisticLockingFailureException` + 최하위 `SQLException`의 MariaDB 코드 **1205**만 인정한다. deadlock(1213)·임의 예외를 성공으로 흡수하지 않는다. 세션 timeout은 원래 값으로 복원한다.
- 첫 latch 대기도 try/finally 안에 두고, release·bounded worker 종료 대기·Future 예외 전파·test advice 제거를 수행한다. fixture 삭제 실패도 더 이상 무시하지 않는다. 부모/자식 시험도 예상 business exception 외에는 실패로 전파한다.
- 계획 실행 기록과 troubleshooting의 barrier 동등성 주장을 정정했다. 과거 수치를 이번 실행 결과로 재사용하지 않는다.

### 재검증

실제 MariaDB 10.11 Testcontainers에서 `MenuConcurrencyIntegrationTest` 4개 통과(실패/오류/skip 0). 단독 실행에서 A-first/B-first 모두 `holder=5 waiter=6`에 해당하는 DB lock wait를 관측했다. primitive 시험은 MariaDB 1205와 실패 transaction의 상태 불변을 확인했다. 부모/자식 불변식도 통과했다.

테스트 계측은 일회용 MariaDB 컨테이너 전용이다. 관측 연결만 root/PROCESS 권한을 사용하며 제품 계정 권한은 바꾸지 않는다. 모든 장애에서 OS/JDBC가 강제 종료된다는 보장은 아니며, bounded 종료 대기 초과는 테스트 실패로 드러낸다. 실제 복구/운영 Gate 전체의 대체물도 아니다.

## 실행 결과 및 최종 판정

이번 작업에서 실제 실행한 결과(2026-09-28):

| 실행 | 결과 |
|---|---|
| `gradlew.bat compileJava compileTestJava test --tests com.cms.admin.menu.service.MenuConcurrencyIntegrationTest --console=plain` | 컴파일 성공, MariaDB 시험 4개 통과, 실패/오류/skip 0 |
| `gradlew.bat test --console=plain` | BUILD SUCCESSFUL, 764개 중 763개 통과 / 1개 skip / 실패·오류 0 |
| `bash -n scripts/tests/quiesced-runbook-test.sh` | 구문 검사 통과 |
| `bash scripts/tests/quiesced-runbook-test.sh` | 문서 실행 블록의 12개 시나리오 통과 |
| `git diff --check` | 통과 |

전체 스위트에서도 양방향 DB lock wait를 다시 관측했고, 기존 `MenuServiceTest` 32개, `MenuControllerTest` 28개, `AdminSidebarAdviceTest` 4개, `SecurityConfigTest` 34개를 함께 통과했다. skip 1개는 기존 `LocalDiskFileStorageTest`의 "심볼릭 링크를 통한 루트 탈출은 거부된다" 시험이다(이번 변경과 무관).

단독 MariaDB 컨테이너 ID는 `a918d628b84e824152a760ee37fdbbcd478ad33ed69ce07b2db60f0c09338865`, 전체 스위트 컨테이너 ID는 `553b7933c8e8129238deb815f2280f39ce307909f07202aedc14acebe9944162`였다. 종료 후 두 컨테이너 및 Ryuk의 제거를 확인했다. 기존 컨테이너/볼륨을 수동 삭제하지 않았다.

재개 직후 첫 Java 시험은 Docker daemon 미기동으로 Testcontainers 초기화에서 실패했다(본문 미실행). Docker Desktop 시작 후 위 단독 시험과 전체 스위트가 통과했다. Bash의 첫 시도도 Git Bash 도구 경로 누락으로 실패했으며, `/usr/bin:/bin`이 포함된 Bash PATH에서 위 명령들을 재실행했다. 제품 수정으로 우회하지 않았다.

| Finding | 판정 | 종결 근거 |
|---|---|---|
| PR6-R1 | **CLOSED** | 실패 분기·원래 상태·backup/recovery 결과 분리, 실제 문서 블록의 12개 대체 시험 |
| PR4-T1 | **CLOSED** | 영구 A-first/B-first 서비스 시험 + DB lock wait 관측, 구체적 timeout 단언, worker/fixture 정리 및 문서 정정 |

**Gate A~H 진입 가능.** 이번 두 finding으로 인한 blocker는 해소됐다. 다른 finding을 다시 감사하거나 과거 전체 Gate 결과를 재인증한 판정은 아니다. 제품 구현을 다시 열 필요 없이 **Gate A~H Production Readiness Verification**으로 진행한다.

**남은 범위:** Gate 전체를 이번에 통과한 것은 아니다. 특히 수정 런북을 사용하는 실제 격리 backup/restore·파일/hash·권한·실패 복구 검증(Gate G) 및 실제 ingress/TLS/client IP 경계 검증(Gate H)은 여전히 별도 수행해야 한다. 기존 보고서의 다른 Gate-only 항목도 유지한다. 전체 테스트 성공만으로 외부 production-ready를 선언하지 않는다.
