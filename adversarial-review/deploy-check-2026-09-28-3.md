# PRODUCTION READINESS VERIFICATION

## Executive Summary

**Final Verdict: NOT READY FOR EXTERNAL PRODUCTION. Gate A~G PASS, Gate H NOT RUN.**

디스크 공간 확보 후 중단된 동일 Gate 검증을 재개했다. Docker 저장소 쓰기와 최종 runtime 이미지 빌드가 정상화됐고, 이전에 회수하지 못한 Linux symlink 테스트 XML도 확보했다. 이후 최종 RC로 browser/session, prod startup, MariaDB concurrency, HTTP/logging, SMTP, 격리 backup/restore를 실행했다. 이번 실행에서 미해결 제품 결함으로 FAIL 판정한 Gate는 없다.

사용자는 실제 production ingress/TLS가 **아직 없다**고 확인했다. 따라서 H는 NOT RUN이며 외부 배포를 승인하지 않는다. 이전 `deploy-check-2026-09-28-2.md`는 중단 당시 이력으로 보존한다. 그 보고서의 환경 장애는 이번 실행에서 해소됐지만, 과거 구현 검증 결과를 이번 실기 결과로 재사용한 것은 아니다.

`deploy-check` 절차를 사용하되 사용자가 지정한 Gate 범위만 검증했다. 제품 코드·설정·migration·dependency·제품 backup/restore script는 수정하지 않았다. 저장소에는 이 보고서만 추가했다. 외부 scratch의 검증 도구와 합성 시험 데이터는 사용자 요청의 실기 검증에만 사용했다.

### RC / 증거 기준

- HEAD: `ec6336cb5ea3aaaa80d94efb88555e9b381da810`.
- PR6-R1/PR4-T1의 기존 미커밋 문서·테스트 보완을 포함한 working tree. 사용자 roadmap/portfolio 등 기존 변경은 그대로 보존했다.
- 최종 이미지: `cms-gate-rc:20260928`.
- OCI index digest: `sha256:535731d85f49831a672408e2720736cfaed008b19bd669e482bc0ec3c8d8c0f0`.
- 이미지 config digest: `sha256:0edfbea49dc17a1d202f5c867d383b2b3e4e277d8fcd447d7d9b0a34f83d62ce`. 별도 daemon의 `docker load` 후 image ID는 이 config digest다. 재빌드한 다른 RC가 아니다.
- 이미지 내부 JAR SHA-256: `D7A50477B5CAE6BC2C539A2110432D3352278EB2D518ABE78D71B8FAEC3D1E5C`.
- 현재 소스·제품 설정·스크립트와 빌드용 사본의 대조에서 불일치 0. 제품 영역의 HEAD 대비 working diff도 없음.
- 증거 루트: `C:/Users/user/.codex/visualizations/2026/09/22/01a0c7fd-e661-7ba0-8967-d738bf885e39/gates-20260928`.
- 증거 루트의 `gate-*.json`, logs, browser PNG, XML, 검증 harness 및 합성 백업을 보존했다. 이후 제품/환경 변경 시 이 결과를 새 RC에 자동 승계하지 않는다.

## Gate Summary

| Gate | Result | Evidence | Blocking Issue |
|---|---|---|---|
| A — Build / Test / Migration | **PASS** | clean compile/full test/bootJar 성공. Windows 764개: 실패·오류 0, skip 1. Linux storage 13개: skip·실패·오류 0. 최종 이미지 성공. 새 MariaDB V1~V11 적용·validate 및 JPA schema validate 성공 | 없음. 제공되지 않은 실운영 DB의 drift까지 인증한 것은 아님 |
| B — Security / Browser Regression | **PASS** | 실제 Chromium, 별도 ADMIN/MANAGER/무인증 session. 저장→DB→ADMIN DOM, 이메일 복사, 프로필/모달, 401/403/CSRF, logout/replay, reset/session 무효화 통과 | 없음 |
| C — Startup / Recovery | **PASS** | 최종 이미지 prod + 독립 schema 11상태. credentials 유무, 프로세스/health, 기존 전체 member 행 불변, 신규 ADMIN 생성, 실제 login/reset 복구 확인 | 없음 |
| D — Concurrency | **PASS** | 실제 MariaDB + 최종 RC HTTP. 양방향 same-row·부모/자식·생성 경합, 일반 수정끼리, 실제 DB lock wait, timeout 409와 데이터 rollback 확인 | 없음 |
| E — Error Contract / Logging | **PASS** | Security 포함 HTTP matrix, 405 Allow, JSON/HTML 구분, 실제 `json-file` 수집 로그에서 diagnostic 및 민감 표식 비노출 확인 | 없음 |
| F — SMTP | **PASS** | 합성 `.env.prod`→원본 compose→container→실제 mail Bean. 10000/30000/30000ms, 빈 값·override·invalid 판정, 정상 test SMTP, read/TLS/write 지연·token cleanup 확인 | 없음. 실제 외부 SMTP 계정의 접속/인증을 시험했다는 뜻은 아님 |
| G — Backup / Restore | **PASS** | 전용 nested daemon의 합성 데이터로 실제 runbook/scripts 실행. 앱 상태 보존, 결과 분리, DB/파일/권한/노출 경계 복귀, checksum 거절, 파괴적 단계 실패 안전성 및 수동 재복구 확인 | 없음 |
| H — Ingress / TLS | **NOT RUN** | 사용자 확인: 실제 ingress/TLS 미구축. 현행 계약/체크리스트만 존재 | 외부 production 승인 차단 |

PASS는 위 RC와 명시한 시험 조건의 증거에 한정한다. 실제 ingress가 없으므로 loopback 시험을 TLS·trusted proxy 검증으로 간주하지 않는다.

## Gate A — Build / Test / Migration

- 이번 Gate 요청의 **중단 전 실행**: `gradlew.bat clean compileJava compileTestJava test bootJar --console=plain`, `BUILD SUCCESSFUL in 2m 11s`, 7 tasks executed. 재개 시 같은 RC임을 확인하고 XML을 다시 집계·보존했다. 공간 확보 후 전체 스위트를 또 실행했다고 주장하지 않는다.
- 집계: tests 764 / failures 0 / errors 0 / skipped 1. Windows skip은 `LocalDiskFileStorageTest.load_symlinkEscape_rejected`의 symlink 생성 환경 제약이다.
- Linux builder에서 실행한 `LocalDiskFileStorageTest`의 XML을 이번에 정상 회수: 13 / failures 0 / errors 0 / skipped 0. symlink escape testcase가 실제 실행됐다.
- `windows-test-results/`, `linux-storage.xml`에 보존. Linux full suite가 아니라 **Windows full suite + Linux storage 대체 검증**이다.
- Windows JAR SHA-256: `572E3716F326D2C2B70A7CE7331972F9526D5F859498577C291E442E8C809E3E`. Linux 이미지 JAR과 별도 빌드 산출물임을 구분한다.
- Dockerfile 최종 runtime stage 성공. 실제 RC를 새 `gate_seed` DB에서 prod로 기동하여 migration 11개 적용, Flyway validate, JPA validate 및 health를 확인했다. G에서도 별도 새 DB 초기화와 복원 후 validate를 확인했다.
- `git log --diff-filter=M -- src/main/resources/db/migration` 결과 없음. 현재 migration working diff도 없음.
- 이전 BuildKit read-only 오류는 재현되지 않았다. 정리 후 C: 여유 약 52.3GB(decimal). 디스크/기존 개발 DB의 포괄적 무결성 감사를 수행한 것은 아니다.

## Gate B — Security / Browser Regression

증거: `gate-b.json`, `gate-b-0.png`~`gate-b-2.png`, `gate-b-app.log`, `gate.mjs`.

- MANAGER가 자기 이름을 실제 API로 저장하고 DB 원문을 대조했다.
- `<span id="audit-name-marker">검증 이름</span>`을 ADMIN 상세에서 문자 그대로 표시, marker DOM 수 0. 정상 이름 및 한글·따옴표·`&`·`&lt;` 원문도 보존했다.
- 상세/요약, 이메일 버튼의 내부 아이콘 클릭과 실제 clipboard 값, 프로필 preset 이미지 로드, 편집/취소/모달 닫기 확인.
- MANAGER→ADMIN API 403, 무인증 API 401, CSRF 누락 변경 요청 403.
- 실제 form login/logout, 이전 session cookie 재사용 401.
- 로컬 MailHog로 reset 메일을 수신하고 링크 token으로 reset 204, token 재사용 400, 기존 session 401, 새 비밀번호 login 성공.
- 관측한 pageerror 0. 외부 사용자에게 메일을 보내지 않았다. HTTPS origin/cookie 속성은 H의 미실행 항목으로 남긴다.

## Gate C — Startup / Recovery

증거: `gate-c.json`, 상태별 `gate-c-*.log`, `startup.mjs`.

| 독립 초기 상태 | bootstrap credential 없음 | 새 credential 제공 | 기동 시 기존 계정 | 실제 recovery 요청 |
|---|---|---|---|---|
| empty | fail-fast 종료 | 새 ACTIVE ADMIN 1명 생성·정상 기동 | 해당 없음 | 해당 없음 |
| MANAGER only | fail-fast 종료 | 새 ADMIN 1명 생성·정상 기동 | 불변 | MANAGER를 ADMIN으로 변경하지 않음 |
| ACTIVE ADMIN | 정상 기동 | 불필요 | 불변 | 기존 정책 유지 |
| auto LOCKED, 미만료 | 정상 기동 | 불필요 | 불변 | login/reset 차단 |
| auto LOCKED, 만료 | 정상 기동 | 불필요 | **LOCKED 그대로** | 이후 login의 lazy unlock에서만 ACTIVE |
| manual LOCKED | 정상 기동 | 불필요 | LOCKED/lockedAt 그대로 | login/reset 차단 |
| PASSWORD_EXPIRED | 정상 기동 | 불필요 | EXPIRED 그대로 | login 차단, 기존 reset 성공 후 복구 |
| DISABLED only | fail-fast 종료 | 별도 새 ADMIN 1명 생성 | DISABLED 불변 | 기존 계정 login/reset 차단 |
| DELETED only | fail-fast 종료 | 별도 새 ADMIN 1명 생성 | DELETED 불변 | 기존 계정 login/reset 차단 |
| ACTIVE + LOCKED | 정상 기동 | 불필요 | 모두 불변 | 기동과 login 허용을 구분 |
| LOCKED + PASSWORD_EXPIRED | 정상 기동 | 불필요 | 모두 불변 | 기동과 login 허용을 구분 |

각 schema에서 기동 전과 **첫 login/reset 전** 기존 member 전체 행을 비교했다. status/password hash/email/lockedAt/passwordChangedAt/reset token/expiry를 포함하여 동일했다. 성공 시 프로세스 생존과 반복 health를 확인했다. fail-fast 종료를 숨기지 않도록 시험 컨테이너의 자동 restart는 사용하지 않았다. 앱은 prod만 사용했으며 dev seed를 활성화하지 않았다.

## Gate D — Concurrency

증거: `gate-d.json`, `concurrency.mjs`, 관련 app log. 영구 concurrency tests는 A의 전체 스위트에서 실행됐으며 아래는 추가 RC 실기다.

- A-first: 일반 수정의 row lock 뒤 비활성화가 대기 → 200/204 → 이름 변경 보존, `useYn=false`.
- B-first: 비활성화 lock 뒤 일반 수정 대기 → 204/200 → 최신 비활성 상태 유지, 이름 변경 보존.
- 부모 비활성화 먼저 / 자식 재활성화 먼저: 각각 204/400 및 200/409. 비활성 부모 아래 활성 자식이 남지 않음.
- 부모 비활성화 먼저 / 자식 생성 먼저: 각각 204/400 및 201/409. 부적합 생성/비활성화가 성공으로 처리되지 않음.
- 같은 행 일반 수정끼리: 이름과 설명 변경 모두 보존, 200/200.
- 실제 row lock timeout: HTTP 409 `RESOURCE_CONFLICT`; 대상 전체 행 불변으로 rollback 확인.

격리 DB에만 임시 trigger/named mutex를 설치해 첫 실제 UPDATE/INSERT의 진행을 제어했다. 두 번째 실제 HTTP 요청의 `SELECT ... FOR UPDATE`를 `INNODB_LOCK_WAITS`와 별도 transaction/connection 식별로 관측한 뒤 첫 작업을 해제했다. Future 미완료·sleep만으로 통과시키지 않았다. trigger/worker는 정리했다. timeout 시험의 DB 설정 변경도 시험 DB에만 적용하고 원래 값으로 복구했다. 예상 밖 deadlock/예외를 성공으로 흡수하지 않았다.

## Gate E — Error Contract / Logging

증거: `gate-e.json`, `gate-e-docker.log`, `errors.mjs`.

| 조건 | 실제 HTTP / code |
|---|---|
| malformed JSON | 400 / JSON_PARSE_ERROR |
| validation | 400 / VALIDATION_ERROR |
| invalid path | 400 / INVALID_REQUEST |
| unauthenticated | 401 / UNAUTHORIZED |
| forbidden / CSRF | 403 / ACCESS_DENIED |
| missing resource | 404 / RESOURCE_NOT_FOUND |
| unsupported method | 405 / METHOD_NOT_ALLOWED, `Allow: GET,PATCH,DELETE` |
| duplicate | 409 / DUPLICATE_RESOURCE |
| unsupported media | 415 / UNSUPPORTED_MEDIA_TYPE |
| rate limit | 429 / RATE_LIMITED |
| unexpected failure | 500 / INTERNAL_ERROR |

API JSON 응답, HTML Accept에도 API JSON 유지, public HTML 404/429/500을 확인했다. 무인증 invalid-path 요청이 MVC 400보다 Security 401로 처리됐다.

격리 DB의 테이블을 잠시 rename해 실제 서버 오류를 발생시켰으며 finally에서 원복했다. Docker `json-file` 수집 로그에서 method/route pattern/exception class/제한된 stack 위치를 포함한 API diagnostic을 확인했다. 해당 요청의 application diagnostic은 1회였다. Hibernate 자체 오류 로그까지 전혀 없다는 주장은 아니다.

password/token/Authorization/cookie/body/raw query에 넣은 시험 표식과 시험 비밀번호가 수집 로그에 없는지 확인했다. 민감 exception message/cause 비출력 회귀는 A의 관련 테스트와 실제 handler 출력 형태를 함께 대조했다. 이 결과를 모든 미래 예외·모든 third-party logger의 무조건적 비노출 보증으로 확대하지 않는다.

## Gate F — SMTP

증거: `gate-f.json`, `gate-f-*.log`, `mail.mjs`, `mail-connect.mjs`, `MailProbe.java`, `FaultSmtp.java`.

합성 자격증명을 사용한 `.env.prod`를 **현재 원본 production compose**로 해석한 뒤 그 환경을 최종 이미지에 전달했다. fixture DB/SMTP 주소만 격리 대상에 맞췄다. 실제 Spring prod context의 `JavaMailSenderImpl` 속성과 컨테이너 환경을 함께 읽었다. YAML 문자열 검사로 대체하지 않았다.

| 조건 | 실제 connection/read/write(ms) / 결과 |
|---|---|
| 누락/default | 10000 / 30000 / 30000 |
| compose 빈 값 | 10000 / 30000 / 30000 |
| 시험 override | 11000 / 31000 / 32000 |
| invalid 시험 | `NaN / -1 / 0` 그대로 Bean에 전달됨. **배포 승인 predicate에서 거절** |
| 실제 `_prod-env-guard.sh` | host override 세 키 unset 확인 |

invalid 값이 Spring 기동에서 자동 거절되거나 안전한 값으로 자동 복구된다고 판단하지 않는다. 현재 승인 계약대로 배포자가 최종 resolved 값과 승인값을 대조해야 한다. default 시험 산출물은 승인값과 정확히 일치했다.

| 장애/정상 조건 | 결과 |
|---|---|
| 정상 로컬 test SMTP | 실제 reset 메일 수신 및 B의 reset 완료 |
| connection refused | 약 1.18초 내 실패 관측, 요청 200 계약·token cleanup 유지 |
| 연결 후 greeting stall | 약 30.53초 종료, token cleanup |
| STARTTLS negotiation stall | 약 31.09초 종료, token cleanup |
| DATA 후 응답 stall | 약 30.61초 종료, 이전 요청 실패가 이후 token을 삭제하지 않음 |
| 실제 write/backpressure | 약 30.39초 종료, 실제 write timeout 확인 |

write 시험만 작은 reset 메일의 OS buffer 흡수를 피하도록 큰 합성 본문·작은 socket buffer를 사용하고, read timeout은 더 길게 두어 write timeout과 구분했다. 제품 발송 본문/구성은 수정하지 않았다. latest-token 시험은 먼저 발급된 메일이 대기하는 동안 DB의 token을 더 최신 fixture로 바꿔 조건부 정리를 검증했다.

**검증 경계:** 실제 TCP SYN blackhole에 의한 connection timeout 실측, 외부 SMTP 서비스의 계정 인증/인증서/성공한 TLS 메일 전송은 수행하지 않았다. 사용자가 허용한 로컬 test SMTP와 승인 timeout의 유한 socket 동작을 검증한 것이다. 외부 실제 사용자 수신자는 없다. executor/broker/retry queue 변경 없음.

## Gate G — Backup / Restore

증거: `gate-g.json`, `gate-g-isolation.json`, `gate-g-script-integrity.json`, `gate-g-*.log`, `recovery-backups/`, `recovery.mjs`.

### 격리 및 실행 조건

- 기존 Docker daemon과 **다른 ID**의 전용 nested Docker daemon에서 수행했다. 별도 전용 data volume만 mount했고 기존 Docker socket·운영/개발 DB·파일 volume은 mount하지 않았다.
- 원본 고정 이름 `cms-app-prod`, `cms-db-prod`, `cms_notice_attachments_prod`는 nested daemon 내부에만 생성했다. 외부 개발 stack과 겹치지 않는다.
- script 실행 shell도 nested 환경 안에 있으므로 script의 `127.0.0.1:8080` health 대상이 실제 drill 앱과 일치했다. remote context만 바꾼 시험이 아니다.
- Linux Bash/GNU 도구 환경. Windows CRLF 작업복사본은 시험 사본에서 LF로 정규화했다. 제품 script/Makefile은 LF 정규화 SHA-256까지 원본과 일치했다.
- 초기 준비 중 profile namespace 누락, DELETE 응답 204 오인, CRLF 전달, Alpine BusyBox find의 GNU option 미지원이 있었다. 모두 fixture/환경 준비 문제로 구분했고 제품을 수정하지 않았다. 필요한 GNU find를 시험 환경에 설치한 뒤 전체 흐름을 다시 실행했다.

### 결과

1. 실제 문서의 quiesced block을 추출해 실행. 원래 실행 상태 기록 → app stop → 실제 stopped 확인 → DB는 유지 → dump/file tar → app 재개/health 성공.
2. `BACKUP=SUCCESS`, `BACKUP_EXIT_CODE=0`, `APP_RECOVERY=SUCCESS`를 각각 기록. 정지 시작 전부터 안정 health까지 **18.151초**.
3. 원래 앱이 정지된 상태에서도 같은 block 실행. 백업은 성공하고 `NOT_REQUIRED_ORIGINALLY_STOPPED`, 실제 앱은 계속 정지.
4. 잘못된 retention 값으로 실제 backup 실패 유도. `BACKUP=FAILED`, `APP_RECOVERY=SUCCESS`, 전체 exit 2. 앱 재개 성공이 백업 실패를 덮지 않음.
5. 백업 후 공지 제목 변경, 비공개 첨부 삭제, 업로드 프로필을 preset으로 교체하여 복원 전후 차이를 실제로 만들었다.
6. 체크섬을 손상시킨 별도 합성 사본은 파괴적 단계 이전 거절. 앱 실행 상태와 변경 후 DB/파일 상태를 유지했다.
7. 유효한 원본 backup으로 실제 restore 수행. 공지/첨부/프로필 DB 참조와 데이터 복귀 확인.
8. DB가 참조하는 파일 **전수 3개**(공개 첨부, 비공개 첨부, 업로드 프로필)의 존재·SHA-256·UID/GID `10001:10001`이 백업 전과 동일.
9. 공개 공지/첨부 무인증 200, 비공개 공지/첨부 무인증 404, 로그인한 관리자의 비공개 첨부·프로필 다운로드 성공 및 내용 hash 일치.
10. 앱 사용자 권한으로 새 파일 업로드·다운로드·삭제. 삭제 후 DB reference와 실제 파일 부재 확인.
11. Flyway 11개 validate, JPA/최종 startup/반복 health 성공. restore 호출부터 파일/API/권한 검증 완료까지 **전체 RTO 41.203초**. 작은 합성 fixture에서의 관측값이며 실운영 데이터량의 RTO 보장은 아니다.
12. 별도 신뢰된 합성 backup 사본의 SQL 끝에 의도된 오류를 넣고 checksum을 재계산했다. 정상 선검사·안전 backup을 통과한 뒤 실제 DB restore 중 실패를 유도했다. 원본 유효 backup은 변경하지 않았다.
13. 파괴적 단계 이후 실패 시 non-zero, 앱 정지 유지, 안전 backup 경로 안내, 정상 자동 재기동 미실행, 작업 lock 해제 확인.
14. 실패를 무시하고 app만 시작하지 않았다. 별도의 명시적 수동 restore를 원본 유효 backup으로 수행하고 data/hash/health를 다시 확인했다.

백업 성공과 app recovery 성공은 독립 결과로 보존했다. health 200만으로 복구 성공을 판정하지 않았다.

## Gate H — Ingress / TLS

**NOT RUN.** 사용자 답변: “아직 없음 — Gate H는 NOT RUN 유지”.

제품 중립 계약은 `docs/verification/deployment-edge.md`에 존재하지만 실제 TLS termination, trusted proxy, direct access 차단, forwarded header 위조 방어, 두 외부 IP quota 분리, audit IP, scheme/cookie/redirect/mixed content, 최종 HTTPS reset origin은 이번에 검증하지 못했다.

운영 준비 공백이지 새로운 제품 결함 finding으로 만들지 않는다. 실제 topology·도메인·인증서·서로 다른 외부 client IP가 준비되면 그 경로에서 H를 실행한다. ingress 통합에서 설정이 바뀌면 영향받는 B/E/F의 관련 경로도 재확인한다.

## Finding Status

| Finding | Status |
|---|---|
| C-01 | **CLOSED** — 최종 RC browser 경로에서 재발 없음 |
| H-01 | **CLOSED** — 11상태 prod startup 및 recovery 정책 일치 |
| M-02 | **CLOSED** — 승인된 SMTP socket timeout 적용·fault 동작 확인 |
| M-03 | **CLOSED** — HTTP contract 및 안전 diagnostic 확인 |
| M-04 | **CLOSED** — 실제 DB lock/HTTP 경합에서 lost update 없음 |
| M-01 | **VERIFIED** — 정규 quiesced recovery와 실패 안전성 실증 |
| M-05 | **NOT VERIFIED** — 실제 ingress 미구축, H NOT RUN |
| M-06 | **REJECTED / NO ACTION** — 관련 제품 변경 없음 |

PR6-R1/PR4-T1 CLOSED 유지. 이 판정은 미래 데이터량·다른 RC·미제공 운영 환경 전체에 대한 포괄 보증이 아니다.

## Blocking Issues

### GATE-H-NOT-RUN — 외부 ingress/TLS 검증 부재

- 원인: 실제 production ingress/topology가 아직 없다.
- 영향: 네트워크 신뢰 경계, HTTPS/session cookie, 실제 client IP와 최종 reset 링크 origin을 인증할 수 없다. 외부 production 공개 불가.
- 최소 후속 범위: 사용자 승인된 실제 ingress 구성의 준비 및 기존 deployment-edge checklist 실행. 임의 proxy 제품 채택·generic IP abstraction·새 제품 리팩터링은 제안하지 않는다.
- 재검증: 실제 외부 hop과 두 client IP에서 Gate H 전체를 실행하고, 바뀐 deployment 설정에 따른 관련 security/SMTP 경계를 재확인한다.

**No-Ship Findings(새 제품 결함): 없음.** 이전 GATE-A-ENV 장애는 해소됐다. **Needs-Attention / 운영 준비 공백: H 미실행**이며 단순 문서 완료로 해소되지 않는다.

## Residual Risks

기존에 수용된 운영 계약만 유지한다.

- online backup은 DB/file 같은 시점 보장이 없는 보조 수단. 정규 recovery 기준은 quiesced backup이며 수동 수행 누락 시 RPO 목표를 보장하지 못한다.
- 같은 호스트의 backup만으로 호스트 전체 유실을 복구할 수 없다. 이번 drill은 오프사이트 복구를 인증하지 않는다.
- file restore는 완전 원자적 교체가 아니다. 중간 실패 시 앱 정지·안전 backup 기반 수동 재복구가 필요하다.
- SMTP 값은 socket 단계 제한이지 DNS/queue/전체 작업 deadline이 아니다. 잘못된 값은 자동 거절되지 않으므로 배포 시 실제 resolved 값 확인이 필요하다.
- 단일 인스턴스/local limiter 및 기존 fail-open trade-off는 그대로다. 이번 Gate에서 분산 인프라를 추가하지 않았다.

## 증거 보존 / 정리 / 변경 범위

- XML·browser 이미지·logs·JSON·합성 유효 backup을 증거 루트에 보존했다. 보고서 밖 검증 harness도 같은 경로에 있다.
- 이번 실행용 app/MariaDB/MailHog/nested Docker 컨테이너 및 해당 시험 DB/전용 nested volume을 소유 확인 후 제거했다. 합성 data는 보존한 backup으로 재구성할 수 있다.
- 기존 `cms-app-dev`/`cms-db-dev`는 실행 상태가 유지됐으며 기존 volume은 삭제하지 않았다. 디스크 정리 명목의 prune은 수행하지 않았다.
- RC/builder 이미지와 종료된 Linux storage 시험 컨테이너는 보존했다. 시험용 실행 컨테이너는 남아 있지 않다.
- 제품 코드·설정·migration·dependency·기존 테스트와 과거 보고서는 이번 단계에서 수정하지 않았다. 새 저장소 산출물은 본 보고서뿐이다.

## Final Verdict

### NOT READY FOR EXTERNAL PRODUCTION

**A~G PASS, H NOT RUN.** 제품 remediation finding은 재발하지 않았고, 격리 환경에서 실제 복구와 실패 안전성까지 확인했다. 남은 배포 차단 조건은 실제 production ingress/TLS 검증이다. H를 통과하기 전에는 SHIP 또는 외부 production-ready로 선언하지 않는다.
