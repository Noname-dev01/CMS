# PLAN — 2단계 인증 암호화 키 교체 (이슈 #258)

- 상태: v5 — 계획 리뷰 4라운드 ship, 사용자 승인 (2026-11-17), 구현·검증 완료(PR 전)
- 위험 변경: **2단계 인증 비밀값 저장(데이터 정합성 — 기존 암호문 재작성)**, **동시성(로그인 2단계와 재암호화의 회원 행 잠금)**, **설정 계약(새 설정 키·운영 환경변수)**
- 스키마 변경: 없음 / 인가 정책 변경: 없음 / 새 의존성: 없음 / API 변경: 없음
- 이슈: #258 (출처 `PLAN-totp-two-factor.md` R4·§7 범위 밖). **이 PR(PR1)에서 #258을 닫고**, 쓰기 형식 `v2` 전환은 PR2(후속 이슈)로 분리한다(§0).
- **운영 전제**: prod는 앱 인스턴스 **1개**다(`docker-compose.prod.yml`의 `app` 서비스 하나, `replicas` 없음). 키 교체는 정지 → 설정 변경 → 기동의 일괄 교체이며, 서로 다른 키 설정의 인스턴스를 동시에 띄우지 않는다(결정 7).

## 0. 사용자 결정 (2026-11-17)

| 쟁점 | 결정 | 기각한 선택지 |
|---|---|---|
| D1 이전 앱 롤백 호환 | **2단계 배포.** PR1은 키 교체 전체(옛 키 설정·기동 재암호화·현재 키 검증)와 `v2` **읽기**를 넣고 **저장은 `v1` 그대로**. PR2가 `v2`(키ID 포함) 쓰기로 전환한다. PR1 배포 뒤 이전 앱으로 롤백해도 저장 형식이 같아 인증 앱 코드가 유지되고, PR2 배포 뒤 PR1로 롤백해도 PR1이 `v2`를 읽는다 | (a) 한 번에 `v2` 전환하고 롤백 영향을 문서화 (c′) PR1은 `v2` 읽기만 — 키 교체를 PR2까지 쓸 수 없다 |
| D2 복호 불가 행 | **건너뛰고 ERROR·WARN 요약 + 기동 계속** | 기동 실패 — 공개 사이트와 복원 스크립트의 자동 기동까지 멈춘다 |

## 1. 정찰 사실 (출처)

- **암호기**: `TotpSecretCipher`(`admin/member/totp/TotpSecretCipher.java`) — AES-256-GCM, 12바이트 무작위 IV, 128비트 태그, AAD `member_totp:{memberId}`, 저장 형식 `v1:` + Base64(IV‖암호문‖태그). 키 하나, 키 식별자 없음. 복호 실패는 모두 `DecryptionException`(상수 메시지, 원인 미첨부).
- **설정**: `TotpProperties`(`cms.totp.encryption-key`, `issuer`) → `TotpConfig.totpSecretCipher` 빈 생성 시 검증 → 틀리면 기동 실패(`TotpConfigTest`가 `ApplicationContextRunner`로 고정). `application.yml` `${CMS_TOTP_ENCRYPTION_KEY:}`, `application-dev.yml` 고정 개발 키.
- **암호화 호출처 1곳**: 등록 확정 `MemberTotpService`(회원 행 잠금 아래). **복호 호출처 1곳**: `TotpCredentialChecker.checkAndConsume`(MANDATORY, 실패 → `UNREADABLE`, 실패 횟수 미증가).
- **잠금 계약**: `MemberTotpRepository` 주석 — 이 행을 바꾸는 모든 경로는 먼저 `member` 행 잠금(`findByIdForUpdate`). 로그인 2단계 `TotpLoginService.verify`도 같다.
- **`MemberTotp`에 `@DynamicUpdate` 없음** — 더티체킹 UPDATE가 모든 컬럼을 쓴다. 잠금 없이 읽은 엔티티로 `secret`만 바꿔 저장하면 동시 로그인이 바꾼 `last_used_step`·`failed_attempts`를 되돌린다 → 재암호화도 잠금 아래에서 **다시 읽은** 엔티티를 바꾼다.
- **콜레이션**: `member_totp`는 `utf8mb4_general_ci`(V17) — SQL 문자열 비교는 대소문자 무시(MariaDB LIKE 문서). PR1은 대상 판정에 SQL 문자열 비교를 쓰지 않는다(결정 3).
- **runner 실행 시점**: Spring Boot 4.0.8 `SpringApplication.run`은 `refreshContext`(웹 서버 시작 포함) → `listeners.started` → `callRunners` → `listeners.ready`(실측: `javap -c` spring-boot-4.0.8.jar 오프셋 113·167·175·212) — runner 중에도 로그인 요청이 들어온다. runner 예외의 기동 영향은 미확인이며, 이 계획은 runner 밖으로 예외를 내보내지 않게 하고 시험으로 고정한다.
- **복원 스크립트**: `scripts/prod-restore.sh`는 복원 뒤 기존 앱 컨테이너를 `docker start`한다(`.env.prod`를 다시 읽지 않음). make 타깃은 없다.
- **운영 환경변수 배선**: `docker-compose.prod.yml` 명시 매핑, `scripts/_prod-env-guard.sh` unset 목록, `.env.example`, CI `scripts/ci/_prod-ci-common.sh` 고정 시험 키.
- **기존 시험 파급**: `TotpSecretCipherTest`(형식 `v1:` 단언 — PR1에서도 유지됨, `rejectsMismatch`의 `"v2:"+…` 단언은 v2 파싱 도입으로 의미가 바뀌므로 재검토), `TotpTestSupport`·통합 시험 5종은 빈 주입이라 영향 없음.
- **잠금 대기 관측 도구**: `src/test/java/com/cms/support/LockWaitObserver.awaitWaiterFor(holder 연결 ID)`.

## 2. 핵심 설계 결정

### 결정 1. 저장 형식 — PR1은 `v1` 쓰기 유지, `v2:{키ID}:{Base64}` 읽기 추가
- **쓰기**: 항상 `v1:` + Base64, 현재 키(기존과 동일 — 이전 앱이 읽는다).
- **읽기**: `v1` → 현재 키, 실패 시 옛 키(GCM 태그가 인증하므로 틀린 키는 실패한다). `v2:{키ID}:…` → 키ID가 현재·옛 키 중 하나와 같으면 그 키로, 아니면 실패. 접두어·키ID는 **대소문자 구분**(`V1:`·`V2:`·대문자 키ID는 형식 오류).
- **키ID** = `SHA-256(원시 키 32바이트)` 앞 8바이트의 16진 소문자 16자. PR1에서는 `v2` 읽기와 **로그 표시**(현재·옛 키ID — 운영자가 보관 기록과 대조)에 쓴다. PR2가 같은 정의로 쓰기를 시작한다.
- 왜 `v2` 읽기를 PR1에 넣는가: PR2 배포 뒤 PR1으로 롤백해도 인증 앱 코드가 유지되게(D1의 목적).

### 결정 2. 옛 키 설정 — `cms.totp.previous-encryption-key` 하나 (선택)
- 환경변수 `CMS_TOTP_PREVIOUS_ENCRYPTION_KEY`, 기본 빈 값 = 없음. 값이 있으면 같은 검증(Base64 32바이트), **틀리면 기동 실패**, **현재 키와 같으면 기동 실패**.
- 하나인 이유: 교체는 "A→B, 완료 확인, 설정에서 A 제거"의 한 단계씩. 섞인 백업 복원은 단계적 수렴(결정 6).

### 결정 3. 재암호화 — 기동 시 일괄(`ApplicationRunner`), 대상은 **현재 키 시험 복호**로 판정, 행 단위 트랜잭션
- `TotpSecretRewrapRunner`(`ApplicationRunner`, 모든 프로파일) → `TotpSecretRewrapService.rewrapAll()`. runner는 **모든 `RuntimeException`을 잡아 ERROR(예외 타입만) 후 정상 반환**(최종 경계).
- `rewrapAll`:
  1. `findAll()`로 전체 행을 읽는다(트랜잭션 밖 읽기 전용, 관리자 수 규모). 행마다 `cipher.decryptWithCurrentKey(id, secret)`가 성공하면 대상 아님(결과 바이트 즉시 0으로). 실패하면 대상. 조회 실패는 runner 경계에서 "조회 실패" ERROR.
  2. 대상 id마다 **`TransactionTemplate.execute` 호출 전체를 try/catch**(콜백 예외·커밋 실패 포함): `findByIdForUpdate(id)` → `memberTotpRepository.findById(id)`(잠금 뒤 다시 읽기) → 없으면 건너뜀 → `decryptWithCurrentKey` 성공이면 건너뜀(그 사이 다른 경로가 처리) → `cipher.decrypt`(현재·옛 키) → `totp.replaceSecret(cipher.encrypt(id, plain))` → 커밋. 평문은 `Arrays.fill(0)`. `DecryptionException` = 복호 불가(행 그대로), 그 밖 = 오류.
  3. **현재 키 검증**: 다시 `findAll()`해 각 행을 현재 키만으로 복호. 실패 memberId를 ERROR로.
  4. 요약 한 줄: `TOTP 키 점검: 현재 키 ID=…, 옛 키 ID=…(없음), 전체 T건, 재암호화 N건, 복호 불가 K건, 오류 E건, 현재 키로 복호 가능 C/T` — 모두 정상이면 INFO, 아니면 WARN.
- 왜 시험 복호인가: `v1`에는 키ID가 없어 문자열로 키를 판별할 수 없다. 행 수가 관리자 수라 기동마다 AES-GCM 복호 T회는 무시할 비용이다. 부수 효과로 대소문자 변형·손상 행이 접두어 비교로 숨지 않는다(접두어가 아니라 인증 복호가 기준).
- 왜 기동 시 일괄·행 잠금인가: 로그인 시 점진 방식은 로그인하지 않는 계정이 남아 옛 키를 뺄 수 없고, 로그인 2단계와 같은 회원 행 잠금으로 직렬화해야 다른 컬럼이 되돌아가지 않는다.
- 현재 키로 이미 읽히는 행(`v1`·`v2` 모두)은 **바꾸지 않는다** — 매 기동 다른 IV로 다시 쓰지 않는다. PR1이 `v2` 행을 만나는 경우는 PR2→PR1 롤백뿐이며, 옛 키 `v2` 행은 `v1`(현재 키)로 다시 쓴다(이 앱의 쓰기 형식).
- 일괄 처리 중 로그인: 옛 키 행은 옛 키로, 다시 쓴 행은 현재 키로 복호 — 끊김 없음.

### 결정 4. 되돌리기(키) — 두 값을 맞바꿔 재기동
- 정지 → `CMS_TOTP_ENCRYPTION_KEY=A`, `CMS_TOTP_PREVIOUS_ENCRYPTION_KEY=B` → 기동 → B 행이 A로 돌아간다.
- **이전 앱으로 롤백**(이 기능이 없는 버전): 저장 형식이 `v1`이라 읽기는 호환되지만 이전 앱은 키를 하나만 받는다. 교체가 끝난 뒤(요약 `T/T`)라면 이전 앱에 **새 키**를 주면 된다. 교체 도중이면 먼저 이 앱으로 끝내거나 되돌린 뒤 롤백한다.

### 결정 5. 완료 확인 — 기동 로그의 검증 결과
- 기준: 요약의 `현재 키로 복호 가능 T/T`, 복호 불가·오류 0. 현재 키만으로 모든 행이 인증 복호된다는 뜻이다.
- DB만으로 키를 판별하는 SQL은 PR1에 없다(`v1`에 키ID 없음) — PR2에서 추가. 운영자는 앱 없이 키ID를 `printf '%s' "$KEY" | base64 -d | sha256sum | cut -c1-16`로 계산해 로그와 대조한다.
- 확인되면 **앱 설정에서** previous를 지우고 재기동 → 다시 `T/T`. **옛 키 자체는 폐기하지 않는다**(결정 6).

### 결정 6. 옛 키 보관과 백업 복원
- "앱 설정에서 제거"와 "보관 키 폐기"는 다르다. 그 키로 암호화된 백업이 보존 기간 안에 있는 동안 옛 키를 **키ID와 함께** 키 보관소(백업과 다른 곳)에 보관한다. 마지막 백업이 파기되면 폐기한다.
- **교체 완료(`T/T`) 확인 전에는 백업을 만들지 않는다** — 행 단위 커밋이라 도중 백업은 두 키가 섞인다.
- 복원 절차:
  1. `bash scripts/prod-restore.sh <백업디렉터리>`는 기존 컨테이너를 그 설정 그대로 `docker start`한다. runner는 복호할 수 없는 행을 **바꾸지 않으므로** 데이터는 안전하다. 기동 로그 요약을 본다.
  2. `T/T`면 끝. 아니면 백업 시점의 키를 보관 기록에서 찾아 `make prod-down` → `.env.prod`에 previous로 → `make prod-up`(재생성 — `start`/`restart`로는 새 환경변수가 반영되지 않는다, "SMTP timeout" 절과 같은 규칙) → `T/T` 확인 → previous 제거.
  3. 그래도 복호 불가가 남으면(섞인 백업) 단계적으로 수렴한다: 예) A·B 혼합 백업을 C 운영 중 복원 → ① current=B, previous=A 기동(요약의 복호 불가가 A·B 어느 쪽에도 안 풀리는 행만 남는지 확인) ② current=C, previous=B → `T/T` ③ previous 제거.

### 결정 7. 단일 인스턴스·일괄 교체
- 키 설정이 다른 인스턴스가 공존하면 서로 반대로 다시 쓰고, 옛 현재 키 인스턴스의 새 등록이 옛 키 행을 만든다. **교체·되돌리기 모두 정지 후 설정을 바꿔 기동**(`make prod-down` → `.env.prod` → `make prod-up`). 여러 인스턴스 도입 시 재검토(문서에 조건으로).

### 결정 8. 복호 불가 진단 순서
- 단정하지 않는다: ① 로그의 현재·옛 키ID를 보관 기록과 대조(누락·오입력·다른 키) ② 백업 복원 직후면 결정 6 ③ 올바른 키를 확보할 수 없거나 행이 손상(형식 오류)됐을 때만 기존 절차(복구 코드 로그인 → 재등록, ADMIN 해제).

### 결정 9. `TotpCredentialChecker`는 바꾸지 않는다
- 두 키 복호는 암호기 내부 변화. 로그인 시 점진 재암호화는 넣지 않는다.

## 3. 작업 단계 (안쪽 → 바깥쪽)
1. `TotpSecretCipher`: 생성자 `(String currentKey, String previousKeyOrNull)`(1인자 생성자 위임 유지), 키ID 계산, `v1` 쓰기, `v1`/`v2` 읽기, `decryptWithCurrentKey`, `currentKeyId()`/`previousKeyId()`(Optional). 같은 키 거부.
2. `MemberTotp.replaceSecret(String)`.
3. `TotpSecretRewrapService`(`admin/member/service`, `TransactionTemplate`, 결과 레코드 `RewrapReport`) + `TotpSecretRewrapRunner`.
4. `TotpProperties`·`TotpConfig` 옛 키 배선, `application.yml`, `docker-compose.prod.yml`(`${CMS_TOTP_PREVIOUS_ENCRYPTION_KEY-}`), `_prod-env-guard.sh`, `.env.example`.
5. 문서: `docs/deployment.md` "2단계 인증(TOTP)"(교체·완료 확인·되돌리기·이전 앱 롤백·옛 키 보관·복원·진단)과 환경변수 표, `admin/member/CLAUDE.md`, `application.yml`·compose 주석.
6. 후속 이슈 초안(8단계에서 승인 후 등록): "TOTP 비밀값 저장 형식 v2(키ID) 전환" — 쓰기 전환·`v1`→`v2` 재작성·키ID SQL 확인.

## 4. 성공 기준 (검증 가능)
- S1. 새 암호문은 `v1:`이고 왕복된다. `v1`(현재·옛 키)·`v2`(현재·옛 키ID) 모두 복호된다. 모르는 키ID·대문자 접두어·대문자 키ID는 복호 불가.
- S2. 옛 키 행이 있는 DB에서 기동하면 요약이 `T/T`, 그 회원의 인증 앱 코드 로그인이 통과하고 `last_used_step`·`failed_attempts`·`enabled_at`가 보존된다.
- S3. 옛 키 설정을 지운 뒤에도 S2의 행이 복호된다.
- S4. 옛 키가 틀린 형식이거나 현재 키와 같으면 기동 실패.
- S5. 복호 불가 행·행 처리 오류·최초 조회 실패가 있어도 runner는 정상 반환하고 나머지 행은 처리되며 요약에 집계된다.
- S6. 실기: dev에서 2단계 등록 → 정지 → 현재=B·옛=A 기동 → 요약 `T/T` → 인증 앱 코드 로그인 → 옛 키 제거 재기동 → 로그인 → 되돌리기(맞바꿔 기동) → 로그인. DB의 `secret`은 계속 `v1:`.

## 5. 실패 모드와 고정 시험 (최대 5)
| # | 실패 모드 | 고정 시험 |
|---|---|---|
| FM1 | 재암호화가 잠금 없이(또는 잠금 전 엔티티로) 저장해 동시 로그인이 바꾼 `last_used_step`·`failed_attempts`를 되돌린다 | `TotpSecretRewrapServiceIntegrationTest`: holder 트랜잭션이 회원 행을 잠그고 값을 바꾼 채 대기 → rewrap을 다른 스레드로 → `LockWaitObserver.awaitWaiterFor(holder 연결 ID)` → holder 커밋 → 결과 행에 바뀐 값 보존 + 현재 키로 복호. 변이: 잠금 제거 → 실패 |
| FM2 | 기존 `v1` 행 또는 PR2가 쓴 `v2` 행을 못 읽어 전원 2단계 실패 | `TotpSecretCipherTest`: 현재 구현으로 만든 실제 `v1` 암호문 상수와 손으로 조립한 `v2` 암호문을 현재·옛 키 위치에서 복호 + 통합 시험에서 옛 키 `v1` 행이 runner 뒤 현재 키 `v1`이 되고 로그인 서비스 통과 |
| FM3 | 옛 키 오설정(형식 오류·현재 키와 동일)을 조용히 무시 | `TotpConfigTest` 두 경우 기동 실패 |
| FM4 | 한 행의 실패(복호 불가·처리 예외)나 최초 조회 실패가 일괄 처리를 멈추거나 기동을 실패시킴 | 통합 시험: 정상(옛 키) 2 + 복호 불가 1 → 2건 다시 씀, 불가 행 원문 유지, 요약 K=1·C=2/3. 단위 시험: runner가 서비스 예외에도 정상 반환, 서비스가 `execute` 예외(실패하는 `PlatformTransactionManager` 스텁 — 커밋 실패 모사)에도 다음 행 진행·E 집계 |
| FM5 | 현재 키 행을 매번 다시 쓰거나(다른 IV) 옛 키 행을 놓침 | 통합 시험: 현재 키 행의 `secret`이 runner 뒤 **같은 문자열**, 옛 키 행만 바뀜 |

## 6. 리스크
- R1. PR2 전까지 DB만으로 행별 키를 알 수 없다 — 확인은 앱 기동 로그에 의존(결정 5).
- R2. runner가 웹 서버 시작 뒤 돌아 health 200 시점에 재암호화가 끝나지 않았을 수 있다 — 두 키가 있어 기능 영향 없음. 완료 확인은 요약 로그를 본 뒤(#247과 같은 성격).
- R3. 키ID는 키 해시의 일부(로그에만 노출) — 키 자체 정보 없음.
- R4. 여러 인스턴스 도입 시 결정 7 전제가 깨진다.

## 7. 범위 밖
- `v2` 쓰기 전환(PR2 후속 이슈), KMS·봉투 암호화, 옛 키 여러 개, ADMIN 수동 재암호화 API, 다중 인스턴스 무중단 교체.

## 개정 이력
- v1 (2026-11-17): 최초 작성.
- v2 변경 (리뷰 1라운드, 7건 모두 수용): ① 단일 인스턴스·정지 후 일괄 교체 전제(결정 7) ② 앱 설정 제거와 키 폐기 구분, 옛 키 보관·백업 복원(결정 6) ③ 대상 판정을 SQL `LIKE`에서 Java 비교로(콜레이션) ④ 현재 키만으로 전 행 복호하는 검증 단계, 완료 기준을 그 결과로 ⑤ runner 최종 catch-all과 `execute` 전체 try/catch, FM4 확장 ⑥ FM1을 `LockWaitObserver`로 ⑦ 복호 불가 진단 순서(결정 8).
- v3 변경 (리뷰 2라운드): ① 혼합 키 백업의 단계적 수렴과 "교체 완료 전 백업 금지" — 수용, 새 시험 요구는 기각(각 단계가 FM이 고정한 같은 교체 동작). ② `prod-restore.sh`의 `docker start` 반영 — 수용, 스크립트 수정·Docker 버전 출처·prod 복원 실기 시험은 기각(runner가 복호 불가 행을 바꾸지 않음, env 반영 규칙은 기존 문서 규칙).
- v4 변경 (리뷰 3라운드 ship): 복원 명령을 `bash scripts/prod-restore.sh <백업디렉터리>`로 교정.
- v5 변경 (사용자 결정, 2026-11-17): D1 = **2단계 배포** — 쓰기를 `v1`로 유지하고 `v2`는 읽기만(결정 1), 대상 판정을 접두어 비교에서 **현재 키 시험 복호**로(결정 3), 키ID SQL 확인을 PR2로 미룸(결정 5), 이전 앱 롤백 절차 추가(결정 4), 복원 절차에서 키ID SQL 제거(결정 6). D2 = 건너뛰고 기동 계속. PR1에서 #258을 닫고 PR2는 후속 이슈.

---

## Context
이슈 #258. `CMS_TOTP_ENCRYPTION_KEY`를 바꾸면 기존 `member_totp.secret`이 모두 복호되지 않아 2단계 사용자 전원이 재등록해야 했다. 옛 키를 함께 받아 기동 때 다시 암호화한다. 사용자 결정으로 2단계 배포의 1단계(쓰기 `v1` 유지, `v2` 읽기만)로 구현했다.

## 핵심 확정 사항 (계획과 달라진 점 포함)
- 계획 v5 그대로 구현했다. 계획과 달라진 결정 없음.
- 재암호화 서비스는 `@Service`이지만 생성자가 `PlatformTransactionManager`를 받아 내부에서 `TransactionTemplate`을 만든다 — 시험이 옛 키를 가진 암호기·실패하는 트랜잭션 관리자로 직접 만들 수 있게(새 Spring 컨텍스트 없음).
- runner 요약은 `RewrapReport.healthy()`(복호 불가·오류 0, `C == T`)로 INFO/WARN을 가른다.

## 구현 파일
- `admin/member/totp/TotpSecretCipher.java` — 2인자 생성자(옛 키 선택, 같은 키 거부), 키ID, `v1` 쓰기, `v1`(현재→옛)·`v2:{키ID}:` 읽기, `decryptWithCurrentKey`.
- `admin/member/totp/TotpProperties.java`·`TotpConfig.java` — `previous-encryption-key` 배선.
- `admin/member/domain/MemberTotp.java` — `replaceSecret`.
- `admin/member/service/TotpSecretRewrapService.java`(신규)·`TotpSecretRewrapRunner.java`(신규).
- 설정: `application.yml`, `docker-compose.prod.yml`, `scripts/_prod-env-guard.sh`, `.env.example`.
- 문서: `docs/deployment.md`(환경변수 표·"2단계 인증 암호화 키 교체"), `admin/member/CLAUDE.md`.
- 시험: `TotpSecretCipherTest`(10건, 구현과 독립된 JDK 봉인으로 `v1`·`v2` 형식 고정), `TotpConfigTest`(+2), `TotpSecretRewrapServiceIntegrationTest`(4건, FM1·FM2·FM4·FM5), `TotpSecretRewrapRunnerTest`(3건).

## 검증 결과
- **영향 범위 시험**(`com.cms.admin.member.*`·`com.cms.config.auth.*`·`*ConventionTest`·`BaselineSchemaTest`): 73개 클래스 611건 통과, 실패 0. 전체 시험은 PR 직전(code-review-loop 마무리)에 한 번 돌린다.
- **변이**: 재암호화의 회원 행 잠금 제거 → FM1 실패, 현재 키 행도 다시 쓰기 → FM5(같은 문자열) 실패, 행 단위 catch 축소 → 커밋 실패 시험 실패, runner catch 축소 → runner 시험 실패. 모두 원복.
- **실기(dev, bootRun + Playwright, 2026-11-17)**:
  1. 키 A(dev)로 기동 → 점검 줄 `현재 키 ID=0b8d6a080fd4c9a3`(셸 `sha256sum|cut -c1-16` 결과와 같음), `Started` 로그 뒤에 실행됨. 보안 설정 화면에서 admin 2단계 등록 → `v1:` 행.
  2. **옛 키 누락**(B만) → 기동 성공, WARN `복호 불가 1건 … 0/1`, ERROR memberId=1, **행 문자열 불변**.
  3. 현재=B·옛=A → INFO `재암호화 1건 … 1/1`, `secret` 변경(여전히 `v1:`), `last_used_step`·`failed_attempts`·`enabled_at` 불변 → 비밀번호 + 인증 앱 코드 로그인 성공(대시보드).
  4. 옛 키 제거(B만) → `재암호화 0건 … 1/1`, 행 문자열 불변 → 로그인 성공.
  5. 되돌리기(현재=A·옛=B) → `재암호화 1건 … 1/1`, 쓴 스텝 보존.
  6. 옛 키 = 현재 키 → 기동 실패(`TOTP 옛 암호화 키가 현재 키와 같습니다`), 포트 미개방.
  7. 원래 dev 설정(A만) → `1/1` → 로그인 성공 → 보안 설정에서 해제(기존 흐름 회귀 확인)로 dev DB 원상 복구(`member_totp` 0행).
- 화면 변경이 없어 브라우저 시험 추가·`-PbrowserTests=only` 실행 대상 아님.

## 이슈
- dev 콘솔 로그의 한글이 CP949로 깨져 보인다(기존 환경 특성) — 확인은 `iconv -f CP949`로 했다.
- 이전 앱(master)으로의 실제 롤백 기동은 하지 않았다 — 쓰기 형식이 기존 `v1`과 같다는 것은 `TotpSecretCipherTest`가 구현과 독립된 JDK 봉인으로 고정한다.

## 후속
- PR2: 쓰기 형식 `v2:{키ID}:` 전환·`v1`→`v2` 재작성·키ID SQL 확인 — 이슈 #269(2026-11-17 등록, 선행: 이 PR 운영 배포).
