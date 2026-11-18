# PLAN — 2단계 인증 암호화 키 교체 (이슈 #258, PR2 이슈 #269)

- 상태: PR1 v5 — 계획 리뷰 4라운드 ship, 사용자 승인, **머지 완료(2026-11-17 · #274 `e17c2ba`)**. **PR2(저장 형식 v2 전환, 이슈 #269)는 문서 끝 "PR2" 절** — v2, 계획 리뷰 2라운드 ship, 구현·검증 완료(PR 전)
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
- v2 변경 (리뷰 1라운드, 4건 모두 수용): ① PS4를 `T/T ⇒ SQL 0건` 한 방향으로, PF3에 접두어 정상·페이로드 손상 행 추가 ② 복원 키ID 조회를 `DISTINCT BINARY`로, 결과는 단서일 뿐임 명시 ③ `BINARY`·콜레이션 출처 링크, 실기에서 버전·digest 기록 ④ 롤백 실기 대상을 `e17c2ba`(#274)로 고정.
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

---

# PR2 — 저장 형식 `v2:{키ID}:` 전환 (이슈 #269)

- 상태: v2 (2026-11-17) — 계획 리뷰 2라운드 ship, 사용자 승인(2026-11-18), 구현·검증 완료(PR 전)
- 위험 변경: **2단계 인증 비밀값 저장(데이터 정합성 — 기존 `v1` 행 전부 재작성)**, **동시성(재작성은 PR1과 같은 회원 행 잠금 경로)**, **배포 호환(이전 앱 롤백 범위 축소)**
- 스키마 변경: 없음 / 인가 정책 변경: 없음 / 새 의존성: 없음 / API 변경: 없음 / 설정 키 변경: 없음
- 선행 조건: 이슈의 "#258 운영 배포"는 **실배포 이력이 없어 충족된 것으로 본다**(사용자 결정 2026-11-17). 첫 실배포에 PR1·PR2가 함께 들어가면 `v1`을 쓰는 운영 버전이 존재하지 않는다.

## P0. 정찰 사실 (출처)
- PR1 머지본(#274): `TotpSecretCipher.encrypt`는 `v1:` 쓰기(`TotpSecretCipher.java:103-115`), `decrypt`는 `v1`(현재→옛)·`v2:{키ID}:`(키ID 일치 키만) 읽기(`:127-159`), 접두어·키ID 대소문자 구분.
- `TotpSecretRewrapService`: 대상 = 현재 키만으로 복호 안 되는 행(`:250-256`), 잠금 뒤 재판정(`:292-298`), 검증 C = 현재 키만으로 복호되는 행 수(`:277-289`). 러너 요약 문구 `현재 키로 복호 가능 C/T`(`TotpSecretRewrapRunner.java:356`).
- 암호화 호출처: 등록 확정 `MemberTotpService`와 재작성 서비스 둘뿐 → `encrypt`만 바꾸면 새 등록도 `v2`.
- `v1` 고정을 단언하는 시험: `TotpSecretCipherTest.roundTrip`·`encryptsWithCurrentKeyOnly`, `MemberTotpServiceIntegrationTest:131`, `TotpSecretRewrapServiceIntegrationTest:128-129`(현재 키 `v1` 행 = 같은 문자열). `'v1:x'` 고정값(`BaselineSchemaTest`·`LoginFailureServiceTest`)은 형식 무관.
- `member_totp` 콜레이션 `utf8mb4_general_ci`(V17) — SQL 문자열 비교는 대소문자 무시. **`BINARY` 피연산자가 있으면 바이트 비교**(출처: MariaDB 공식 문서 [BINARY Operator](https://mariadb.com/docs/server/reference/sql-functions/string-functions/binary-operator)·[콜레이션 `_ci`](https://mariadb.com/docs/server/reference/data-types/string-data-types/character-sets/supported-character-sets-and-collations). 저장소의 운영·시험 이미지는 MariaDB 10.11 digest 고정 — 실기에서 `SELECT VERSION()`과 digest를 함께 기록해 실측).
- `v2:` + 16자 + `:` = **20자** → `LEFT(secret, 20)`.
- V17 주석의 `'v1:'` 문구는 머지된 마이그레이션이라 고치지 않는다.

## P-결정

### P1. 쓰기 — 항상 `v2:{현재 키ID}:` + Base64(IV‖암호문‖태그)
- 페이로드·AAD·키ID 정의는 PR1과 같다. 읽기 경로(`decrypt`)는 **바꾸지 않는다** — PR1(#274) 버전이 그대로 읽는 이유.
- 기각: 쓰기 형식 선택 설정(롤백용 토글) — 실배포 전이라 필요 없고 과설계.

### P2. 재작성 대상 — "현재 키 `v2` 접두어 **그리고** 현재 키 복호 성공"이 아닌 모든 행
- 판정을 암호기 메서드 `isCurrent(memberId, stored)`로 모은다: `stored.startsWith("v2:" + currentKeyId + ":")`(Java, 대소문자 구분) && `decryptWithCurrentKey` 성공(결과 바이트 즉시 0).
- 잠금 뒤 재판정도 같은 메서드. 대상이면 `decrypt`(현재·옛, `v1`·`v2`) → `encrypt`(=`v2` 현재 키).
- 결과: 첫 기동에 기존 `v1` 행(현재 키 포함)이 전부 한 번 `v2`로 바뀌고, 이후 기동에서는 바뀌지 않는다(다른 IV로 매번 다시 쓰지 않음).
- 선택지: (a) 접두어만 — 현재 키ID 표시인데 손상·오표기된 행을 "완료"로 센다. (b) 복호만(PR1) — `v1` 현재 키 행이 남는다. (c) **둘 다(채택)**.
- 대문자 변형(`V1:`·`V2:`·대문자 키ID): 접두어 불일치 → 대상 → 복호 실패 → **바꾸지 않고 "복호 불가"로 집계**(PR1 D2와 같음).

### P3. 완료 검증 C의 정의를 "현재 키 `v2`로 저장됨"(P2의 `isCurrent`)으로 바꾼다
- 이유: 이번에 추가하는 SQL 확인(`LEFT(secret,20) <> BINARY 'v2:<키ID>:'` 0건)과 로그 `T/T`가 같은 뜻이 되게. PR1 정의를 두면 재작성 커밋이 실패한 `v1` 행도 C에 들어가 로그는 `T/T`인데 SQL은 행을 보여 준다(오류 E는 WARN이지만 다음 기동에선 그 행이 다시 대상이 될 뿐 C로는 드러나지 않음).
- 요약 문구를 `현재 키 v2 형식 C/T`로 바꾸고 레코드 필드 `readableWithCurrentKey`→`currentFormat`, `unreadableWithCurrentKeyIds`→`notCurrentFormatIds`로 이름을 맞춘다. ERROR 문구도 "현재 키 v2 형식이 아닌 TOTP 행". 운영 문서의 완료 기준 문구를 함께 고친다(실배포 전이라 기존 로그 해석 호환 부담 없음).
- 기각: 문구 유지·의미만 변경 — 운영자가 "복호 가능"을 형식 무관으로 오해한다.

### P4. "#258 버전이 이 형식을 읽음" 시험
- (a) **채택**: `TotpSecretCipherTest`에 구현과 독립된 JDK 복호기(`open`)로 `encrypt` 결과를 #258의 파싱 규칙(`v2:` + 키ID 16자 + `:` + Base64)대로 열어 원문과 키ID(SHA-256 앞 8바이트)를 확인. `decrypt` 코드는 이 PR에서 diff 0줄 → #274의 `decrypt`와 동일(리뷰에서 diff로 확인).
- (b) 실기: 브랜치로 `v2` 행을 만든 뒤 **#274 머지 커밋 `e17c2ba`의 앱으로 기동해 인증 앱 코드 로그인** — 실제 롤백 확인(계속 바뀌는 master가 아니라 고정 커밋, `git worktree`로 띄움).
- 기각: #274의 클래스를 시험 트리에 복사 — 같은 코드 두 벌 유지.

### P5. 운영 문서(`docs/deployment.md` "2단계 인증 암호화 키 교체")
- 기동 점검 설명·완료 기준을 `현재 키 v2 형식 T/T`로.
- **DB 확인 SQL**: `SELECT member_id FROM member_totp WHERE LEFT(secret, 20) <> BINARY 'v2:<현재 키ID>:';` — 0건이면 모든 행이 현재 키 표시. `BINARY` 필수(콜레이션이 대소문자 무시라 `V2:`·대문자 키ID 행이 숨는다). 이 SQL은 표시만 보고 복호는 보지 않으므로 **점검 줄 `T/T` ⇒ SQL 0건**만 성립하고 역은 아니다(접두어는 맞고 페이로드가 손상된 행은 SQL에 안 잡힌다) — 최종 기준은 점검 줄, SQL은 보조 확인.
- **백업 복원 시 키ID 조회**: `SELECT DISTINCT BINARY SUBSTRING(secret, 1, 20) FROM member_totp;` — `BINARY`가 없으면 콜레이션이 정상·대문자 변형 접두어를 한 그룹으로 합친다. `v2:<키ID>:`면 보관 기록에서 그 키를 찾는다(키를 찾는 **단서**일 뿐 복호된다는 증거가 아니다 — 확인은 점검 줄). `mariadb` 클라이언트의 출력 모양은 실기에서 확인해 문서에 적는다. `v1:`로 시작하는 값(이 버전 전 백업)은 키ID가 없어 기존 절차(점검 줄)로.
- **배포 순서·롤백 범위**: 이 버전은 #274(키 교체 지원) 이후 버전 위에만 배포한다. 배포 뒤 되돌릴 수 있는 버전은 **#274 이후**뿐 — 그 전 버전은 `v2`를 읽지 못해 2단계 등록 회원 전원의 인증 앱 코드가 거부된다(복구 코드 로그인은 가능). PR1의 "이 기능이 없는 이전 앱으로 롤백" 항목을 이 내용으로 바꾼다.
- "저장 형식 메모"를 현행으로. 첫 기동에 전 행이 재작성된다는 점(재작성 N = T)과 그동안에도 두 형식 모두 읽힌다는 점.
- `admin/member/CLAUDE.md` 키 교체 문단 현행화.

### P6. 기동 시점(#247)과의 관계 — 변경 없음
- 러너는 여전히 health 200 뒤에 돈다. 첫 기동 재작성량은 관리자 수(T)로 작고, 재작성 중에도 `v1`·`v2` 모두 읽혀 로그인 영향이 없다. 판정 개선은 #247 범위.

## P-작업 단계
1. `TotpSecretCipher`: `encrypt` → `v2`, `isCurrent(memberId, stored)` 추가, 클래스 주석.
2. `TotpSecretRewrapService`: 대상·재판정·검증을 `isCurrent`로, 레코드 필드 이름. `TotpSecretRewrapRunner` 요약 문구.
3. 시험 수정·추가(P-실패 모드 표).
4. 문서: `docs/deployment.md`, `admin/member/CLAUDE.md`, `plan/README.md` 64행 완료 표시·PR2 행.

## P-성공 기준
- PS1. 새 암호문은 `v2:{현재 키ID}:`로 시작하고 왕복되며, 독립 복호기(#258 규칙)로 열린다.
- PS2. 현재 키 `v1` 행·옛 키 `v1`/`v2` 행이 한 번의 기동 재작성 뒤 모두 `v2:{현재 키ID}:`이고 다른 컬럼 보존, 인증 앱 코드 로그인 통과. 두 번째 실행은 아무 행도 바꾸지 않는다.
- PS3. 대문자 접두어 행은 바뀌지 않고 복호 불가로 집계, C에 들어가지 않는다.
- PS4. 요약 `T/T`이면 DB SQL 0건이다(한 방향 — 역은 보장하지 않는다). 실기에서 둘 다 확인하고 `SELECT VERSION()`·이미지 digest를 기록한다.
- PS5. 실기: 브랜치로 `v2` 행 생성 → `e17c2ba`(#274) 앱 기동 → 인증 앱 코드 로그인 성공.

## P-실패 모드와 고정 시험
| # | 실패 모드 | 고정 시험 |
|---|---|---|
| PF1 | 현재 키 `v1` 행을 재작성 대상에서 놓친다(PR1 판정 잔존) | `TotpSecretRewrapServiceIntegrationTest`: 현재 키 `v1` 행 → `v2:{현재 키ID}:`, 다른 컬럼 보존, 로그인 통과 |
| PF2 | `v2` 현재 키 행을 매 기동 다시 쓴다 | 같은 시험에서 `rewrapAll` 두 번째 호출 뒤 모든 행 문자열 동일, `rewrapped=0` |
| PF3 | 대문자 접두어 행·접두어는 맞지만 페이로드가 손상된 행을 현재 형식으로 세거나 덮어쓴다 | 통합 시험: `V2:{현재 키ID}:…`·`V1:…`·`v2:{현재 키ID}:`+손상 페이로드 행 → 문자열 불변, `unreadable`에 집계, `currentFormat`에서 제외(`C<T`). 손상 행은 SQL 조건(`LEFT(secret,20) <> BINARY …`)에는 걸리지 않음을 같은 시험에서 JDBC로 확인 — PS4가 한 방향인 근거 |
| PF4 | 새 형식이 #274 버전에서 읽히지 않는다 | `TotpSecretCipherTest`: `encrypt` 결과를 독립 JDK 복호기로 #258 규칙대로 열기 + 실기 PS5 |
| PF5 | 검증 C가 `v1` 행을 완료로 센다(로그 `T/T`인데 SQL 행 존재) | 통합 시험: 현재 키 `v1` 두 행 중 첫 커밋 실패 → `errors=1`, `currentFormat = total-1`, `notCurrentFormatIds`에 그 행 |

## P-리스크
- PR-1. 이 버전 배포 뒤 #274 이전 버전으로 롤백하면 2단계 회원 전원 코드 거부 — 문서로 고지(P5), 실배포 전이라 해당 운영 버전 없음.
- PR-2. 첫 기동에 전 행이 다시 쓰여 그 기동 이전 백업과 이후 DB의 암호문이 모두 달라진다 — 키는 같으므로 복원에 영향 없음.
- PR-3. SQL 확인은 표시만 본다 — 손상 행은 점검 줄로만 드러난다(문서에 명시).

## P-범위 밖
- 기동 준비 완료 판정(#247), 다중 인스턴스, 쓰기 형식 토글.

## P-개정 이력
- v1 (2026-11-17): 최초 작성.
- v2 리뷰 2라운드: ship(새 지적 없음).

## PR2 결과

### Context
이슈 #269. #274(PR1)는 롤백 호환을 위해 `v1`로 쓰고 `v2:{키ID}:`는 읽기만 했다. 실배포 이력이 없어 선행 조건 "#258 운영 배포"는 충족된 것으로 보고(사용자 결정) 쓰기를 `v2`로 바꾸고 기존 `v1` 행을 기동 때 `v2`로 다시 쓴다.

### 핵심 확정 사항 (계획과 달라진 점 포함)
- 계획 v2 그대로 구현했다. 계획과 달라진 결정 없음.
- `TotpSecretCipher.decrypt`는 #274(`e17c2ba`)와 diff 0줄 — 바뀐 것은 `encrypt` 접두어와 새 `isCurrent`뿐(`git diff e17c2ba -- TotpSecretCipher.java`로 확인).
- 서비스의 대상 판정·잠금 뒤 재판정·최종 검증 세 곳이 모두 `cipher.isCurrent` 하나를 쓴다.

### 구현 파일
- `admin/member/totp/TotpSecretCipher.java` — `encrypt`가 `v2:{현재 키ID}:`, `isCurrent(memberId, stored)`(접두어 Java 비교 + 현재 키 인증 복호), 클래스 주석.
- `admin/member/service/TotpSecretRewrapService.java` — 판정을 `isCurrent`로, `RewrapReport` 필드 `currentFormat`·`notCurrentFormatIds`, ERROR 문구.
- `admin/member/service/TotpSecretRewrapRunner.java` — 요약 `현재 키 v2 형식 C/T`.
- 문서: `docs/deployment.md`("2단계 인증 암호화 키 교체" — 점검 줄·완료 기준·배포 순서와 롤백 범위·저장 형식·DB 확인 SQL·복원 키ID 조회), `admin/member/CLAUDE.md`, `plan/README.md`(64 완료·66 추가).
- 시험: `TotpSecretCipherTest`(+2: PF4 독립 복호기, `isCurrent` 경계 — 기존 2건 `v2` 단언으로), `TotpSecretRewrapServiceIntegrationTest`(PF1 재작성·PF2 두 번째 실행·PF3 대문자·손상 행과 SQL 조건·PF5 커밋 실패 시 `v1` 행 미집계), `TotpSecretRewrapRunnerTest`(문구), `MemberTotpServiceIntegrationTest`(등록 행 `v2:`).

### 검증 결과
- **TDD**: 암호기 시험 4건·서비스 시험 PF1·PF5가 구현 전 실패 확인 후 통과. PF2·PF3는 구현 전에도 성립하는 동작이라 처음부터 통과(새 판정의 회귀 방지).
- **변이**: `isCurrent`에서 접두어 검사 제거 → PF1·PF5·암호기 경계 시험 실패, 복호 검사 제거 → PF3·암호기 경계 시험 실패. 원복.
- **영향 범위 시험**(`com.cms.admin.member.*`·`com.cms.config.auth.*`·`*ConventionTest`·`BaselineSchemaTest`): 76개 클래스 620건 통과, 실패 0. 전체 시험은 PR 직전(code-review-loop 마무리)에 한 번 돌린다.
- **실기(dev, 2026-11-18, MariaDB `10.11.19-MariaDB-ubu2204`, 이미지 `mariadb:10.11@sha256:d769e379…deae`)**:
  1. `e17c2ba`(#274) 앱을 `git worktree`로 기동 → admin 2단계 등록 → 행 `v1:…`(실제 이전 버전이 쓴 행).
  2. 브랜치 앱 기동 → `TOTP 키 점검: … 전체 1건, 재암호화 1건, 복호 불가 0건, 오류 0건, 현재 키 v2 형식 1/1`, 행 `v2:0b8d6a080fd4c9a3:…`, `enabled_at`·`last_used_step`·`failed_attempts` 불변. `SELECT member_id … WHERE LEFT(secret, 20) <> BINARY 'v2:0b8d6a080fd4c9a3:'` 0건, `SELECT DISTINCT BINARY SUBSTRING(secret,1,20)` → `v2:0b8d6a080fd4c9a3:`(mariadb 클라이언트가 텍스트로 출력). 비밀번호 + 인증 앱 코드 로그인 성공(대시보드).
  3. **롤백**: `e17c2ba` 앱 재기동 → `현재 키로 복호 가능 1/1`, `재암호화 0건`(`v2` 행을 그대로 둠) → 인증 앱 코드 로그인 성공(PS5).
  4. 브랜치 앱 두 번째 기동 → `재암호화 0건 … 1/1`, 암호문 바이트 동일(PF2 실측).
  5. 보안 설정 해제 API(204)로 dev DB 원상 복구 — `member_totp`·`member_totp_recovery_code` 0행, admin `ACTIVE`.
- 화면·URL·인가 변경이 없어 권한 경계 직접 접근 점검과 브라우저 시험(`-PbrowserTests=only`)은 대상 아님. 로그인 화면 회귀는 실기 2·3·5에서 확인.

### 이슈
- `./gradlew bootRun`을 `taskkill`로 내리면 백그라운드 작업이 exit 1로 보고된다(의도한 종료).
- 한글 로그 CP949 표시는 기존 환경 특성(`iconv`로 확인).

### 후속
- 없음(기동 준비 완료 판정은 기존 이슈 #247 범위 — 첫 기동 재작성 증가는 관리자 수 규모).
