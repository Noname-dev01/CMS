# PLAN — 관리자 쪽지(상단바 봉투 + 쪽지함 페이지)

> 상태: **v9 (2026-10-05) — 적대적 리뷰 7라운드 ship**(실질 지적 0건, 지적 추이 13→8→5→6→3→1→0). 참고 2건 반영. **구현은 사용자 승인 후에만 착수**(ship은 계획 검토 통과이며 구현 승인이 아니다)
>
> **개정 이력**
> - v9 변경(7라운드 **ship**, codex `gpt-6.1-sol`, 실질 지적 0건 — 참고 2건 수용, 기각 없음): 리뷰어는 v8의 R6-1 수정이 새 결함을 만들지 않았음을 확인했다 — `member.user_id`가 `utf8mb4_general_ci` 유일 제약(V1)이라 조회·유일성 검사가 같은 비교 규칙을 따르므로 **원문 정확 일치는 최대 1건**이고(정렬·10건 제한·`truncated`·`recipientId` 선택 계약 성립, 회원 `id`로 중복 제거), 정상 생성된 아이디(UTF-16 50 상한)는 검색 원문 상한에 걸리지 않으며, F1~F19·§5-E·§5-I·R17·§7 사이 새 충돌 없음
>   - 참고 수용 ① 검색 버킷(D17)은 **요청당 1회** 소비한다(정확 일치·부분 검색 두 조회가 각각 소비하지 않는다) — §5-E에 명시 ② 개정 이력 R2-8의 "변수 미지원 시 건너뜀"은 **R4-6에서 철회된 과거 계약**이다(현재 본문·시험은 필수 지원 단언)
> - v8 변경(6라운드 no-ship, codex `gpt-6.1-sol`, 지적 1건 + 참고 3건 — 지적·참고 전부 수용, 기각 없음. 단계 1~4 의존성·배지 단일 소유자·R5-1 조건부 보장·F1~F19에서 새 충돌 없음이 확인됨):
>   - R6-1 수용(코드 확인: `AdminSignupRequest`는 아이디에 `@NotBlank`·최대 길이만 검사하고 `AdminMemberService`는 그대로 저장 — 앞뒤 공백 아이디가 정상 데이터): v7이 검색어를 먼저 trim해 `" a"` 같은 계정이 정확 일치에서 빠지고 "자기 아이디로 항상 선택 가능" 보장이 거짓이 된다 → **검색 원문을 보존**하고 **원문 그대로의 아이디 정확 일치를 먼저 조회**(아이디 길이 범위 안일 때), **부분 검색(2~50코드포인트)만 trim 후 적용**한다. 두 경로 모두 같은 수신 가능 필터·자기 제외·D17을 적용하고 화면은 정확 조회 전에 공백을 제거하지 않는다. 보장 문구를 "저장된 아이디를 **원문 그대로** 입력하면 항상 선택 가능"으로 정정(§5-E·§5-I·R17·§7)
>   - 참고 수용: ① 1코드포인트 정확 조회는 **존재 확인 경로**이고 D17은 이를 차단하지 않고 속도만 제한한다(영문·숫자 36개 후보를 초기 30토큰+리필로 약 12초에 순회 가능) — D4가 이미 수용한 범위이지만 R4·R17에 사실대로 기록하고 R4의 "최소 2자"를 "부분 검색 최소 2코드포인트, 1코드포인트는 아이디 정확 일치"로 정정 ② `docs/deployment.md` 운영 전제 기록을 §8 단계 6에 명시
> - v7 변경(5라운드 no-ship, codex `gpt-6.1-sol`, 지적 3건 + 참고 3건 — 전부 수용, 기각 없음. §5-F 조건표와 §5-C·§7의 수신자 변경 서술은 일치하고 F1~F19에서 새 정찰 오류 없음이 확인됨):
>   - R5-1 수용: v6의 "이 스키마에서 사용자 텍스트가 DB 오류 메시지에 들어갈 경로가 없음"은 **JDBC·세션 설정에 의존**한다 — Connector/J 3.5.8은 `dumpQueriesOnException`이 켜지면 SQL을, `includeInnodbStatusInDeadlockExceptions`가 켜지면 InnoDB 상태를 예외 메시지에 추가하고, 운영 `DB_URL`은 외부 값이다. 연결 문자셋·strict mode도 별도 전제(`Incorrect string value`는 입력 일부를 포함할 수 있고 non-strict에서는 값이 조정된다) → 비유출 보장을 **"검증된 JDBC·세션 설정에서의 보장"**으로 정정하고 운영 전제(진단 옵션 2종 비활성·JDBC 쿼리 DEBUG 비활성·연결 문자셋 `utf8mb4`·strict mode)를 `docs/deployment.md` 점검 항목으로 기록, 시험은 테스트 연결의 `@@character_set_client`·`@@sql_mode`(strict)를 단언하고 **4바이트 문자(이모지) 왕복 저장**을 검증(§5-G·R13·§7·§9)
>   - R5-2 수용(코드 확인: `AdminSignupRequest`는 아이디·이름에 `@NotBlank`와 최대 길이만 검사 — 한 글자도 정상 계정): 최소 2코드포인트 규칙이 수신 가능한 한 글자 계정을 선택 불가능하게 만든다 → **1코드포인트 입력은 수신 가능 집합 안에서 아이디 정확 일치만 조회**, 부분 검색은 2코드포인트부터 유지, 횟수 제한은 그대로. 수신 가능 계정은 자기 아이디로 항상 선택 가능(§5-E·§5-I)
>   - R5-3 수용: 상단바 count GET과 쪽지함 PATCH 후 재조회가 경합하면 늦은 최초 응답이 배지를 되돌린다(폴링 없어 복구 불가) → **배지 상태·요청 세대의 소유자를 상단바 스크립트 하나로 통합**하고 쪽지함 JS는 count API를 직접 부르지 않는다: 읽음·삭제 시작 시 기존 count 응답을 무효화, 완료 후 재조회한 값만 반영(§5-I·§7)
>   - 참고 수용 3건: ① §5-G "실패" 항목의 `errorMessage=e.getMessage()` 저장·"모든 예외 메시지는 고정 문구" 문장을 `safeErrorMessage` 선택 속성 정책으로 교체(R4-1에서 통일하지 못한 누락) ② §7의 가드 표현을 "비`ACTIVE`·`ROLE_USER` 전환 403, ADMIN→MANAGER는 허용 집합 안"으로 정정 ③ `com.cms.admin.log` `CLAUDE.md`·`docs/deployment.md`를 §3·§9 문서 목록에 추가
> - v6 변경(4라운드 no-ship, codex `gpt-6.1-sol`, 지적 6건 + 참고 2건 — 전부 수용, 기각 없음. 대부분 시험 장치·보장 범위 정의 문제이고 가드·ON/OFF 컨텍스트 분리의 설계 자체는 새 결함 없이 확인됨):
>   - R4-1 수용: `safeErrorMessage`는 감사 **저장값**만 보호한다 — Hibernate `SqlExceptionHelper`가 `SQLException.getMessage()`를 ERROR로 출력하는 서버 로그는 코드로 막을 수 없다(로거를 끄면 전 기능의 진단이 사라짐) → **감사 저장값과 서버 로그 보호를 분리**하고 로그 쪽 보장은 "이 스키마에서 사용자 텍스트가 DB 오류 메시지에 들어갈 경로가 없음"으로 좁힌다(길이 선검증으로 "Data too long" 없음·유일 키는 자동 증가 PK와 상태 행뿐·FK/CHECK 메시지는 제약·테이블 이름만·바인딩 값 로깅 `BasicBinder` 꺼짐 확인). 시험은 본문 표식이 든 요청에서 FK·CHECK·롤백 실패를 주입해 **로그 출력 전체(메시지·Throwable·cause)에 표식이 없음**을 검사하고, `AdminActionLogAspect`의 감사 저장 실패 경로 `loggingError`도 대상에 포함. §5-G의 남은 "실패는 `e.getMessage()` 저장" 문장을 선택 속성 정책으로 통일(§5-G·R13·§7)
>   - R4-2 수용(코드 확인: 인용한 `MemberPermissionApiIntegrationTest` 프록시는 **실제 `commit()`을 수행한 뒤** 예외를 던진다): 롤백 검증은 **`beforeCommit` 동기화에서 예외를 던져** 실제 커밋 전에 실패시키는 방식으로 바꾼다(`MenuConcurrencyIntegrationTest` 선례). 커밋 응답 유실은 별도 시험·한계로 분리하고 그 경우에는 "저장되지 않았다"고 단언하지 않는다(§7)
>   - R4-3 수용: RC에서는 갭 잠금이 쓰이지 않으므로 RC 서비스의 범위 DELETE 변형은 B를 차단하지 않는다 → 대조군의 A만 **시험 전용 RR 트랜잭션**으로 실행하고 A의 격리 수준을 단언, 인덱스·범위·B 삽입 위치를 고정해 같은 갭을 쓰게 한다. 정상 경로는 RC 유지(§5-F·§7)
>   - R4-4 수용: "검증 후 커밋" 시험의 중단 지점·fixture 미정의, ADMIN 발신자 배치는 진행 불가(교착) → 중단 지점은 **수신자 스칼라 조회 반환 직후·쪽지 INSERT 이전**(시험용 advice), 발신자는 **MANAGER**, 수정 실행자는 별도 ADMIN. 실제 ADMIN↔ADMIN 교착 시험은 분리(§5-F·§7)
>   - R4-5 수용(코드 확인: `AdminMemberUpdateRequest`는 `ROLE_ADMIN`·`ROLE_MANAGER`만 허용 — `ROLE_USER` 전이는 API로 못 만든다): R3-3의 서술을 **조건표**로 통일 — 검증 전 DISABLED 커밋 → 400, 검증 전 ADMIN→MANAGER 커밋 → 수신자 검증 통과(D5), `ROLE_USER`는 SQL fixture 방어 시험, 실제 교착 희생자 → 409. §7의 무조건 "409" 단언 삭제. 개정 이력 R2-1·R3-3의 서술도 이 표를 따른다(§5-F·§7)
>   - R4-6 수용: 변수가 없는 서버는 `connection-init-sql`에서 컨텍스트 기동이 먼저 실패해 "건너뛰기"에 도달하지 못한다 → **건너뛰기 계약 삭제**. 테스트 컨테이너는 이미지 digest 고정이므로 `innodb_snapshot_isolation` 지원을 **필수로 단언**하고, 단계 1에서 고정 이미지의 `SELECT VERSION()`·변수 존재를 확인한다(없으면 지원 버전 10.11.9+로의 이미지 변경을 **사용자 결정으로 올린다** — 시험 이미지 변경은 digest 고정 관례·`scripts/ci/check-image-refs.sh`와의 관계를 단계 1에서 대조)(§5-D·§7·§8)
>   - 참고 수용: `MESSAGE_SEND` 상수·`ALL`·활동 로그 화면 라벨을 **같은 단계(2단계)**에 추가해 `AdminActionTypeLabelSyncTest`가 중간 단계에서 깨지지 않게 한다(코드 확인: 해당 시험 존재). 가드의 "영속성 컨텍스트 사용 금지"는 "**엔티티 로딩·캐시된 엔티티 기반 판정 금지**"로 정정(§5-J·§8)
> - v5 변경(3라운드 no-ship, codex `gpt-6.1-sol`, 지적 5건 — 전부 수용, 기각 없음. 설계 결함 3건 + 시험 장치 2건. 설계의 핵심(RC·상태 행·이력·Aspect 순서·패턴 순회)은 새 결함 없이 확인됨):
>   - R3-1 수용(코드 확인: `AdminActionLogAspect`가 `e.getMessage()`를 그대로 잘라 저장): 서비스 내부 예외를 고정 문구로 만들어도 **서비스 반환 이후 트랜잭션 어드바이저가 던지는 flush·커밋 예외**는 막지 못한다 → `@AdminActionLogged`에 **선택 속성 `safeErrorMessage`**(고정 문구)를 추가해 값이 있으면 Aspect가 `e.getMessage()` 대신 그 문구를 저장하게 하고 `MESSAGE_SEND`만 쓴다(기존 호출부는 속성이 비어 동작 불변). 원래 예외·원인 체인은 그대로 유지해 409 판정에 쓴다. 커밋 단계 실패 주입 시험: 쪽지·이력 롤백·SUCCESS 없음·FAIL 1건·감사 메시지에 입력·SQL 원문 없음(§5-G·§3·§7·§8)
>   - R3-2 수용(v4의 오류 — 서비스만 보고 가드를 놓침): 가드가 외부 트랜잭션에 참여하면 외부 스냅샷의 낡은 상태를 읽어 통과시킬 수 있다 → 가드를 **`REQUIRES_NEW + readOnly + READ_COMMITTED`의 스칼라 조회**(역할·상태)로 외부 트랜잭션·영속성 컨텍스트와 분리. 반례 시험: 외부 스냅샷 생성 → 다른 연결에서 비활성화 커밋 → 가드 호출 → 403(§5-J·§7)
>   - R3-3 수용: 수신자 거부 400(§5-C)과 회원 수정 경합 시험의 결과 집합(201·409만)이 모순 → 순서로 분리: **수신자 변경이 검증 전에 커밋되면 고정 문구 400·쪽지/이력 미저장·FAIL 감사**, 검증 후 변경이나 실제 교착이면 201 또는 409(쪽지 미저장). R2-1 항목의 "409만 허용" 서술도 정정(§5-F·§7)
>   - R3-4 수용: `SET SESSION innodb_snapshot_isolation`은 풀의 물리 연결에 남고 Hikari는 이를 복원하지 않아 다른 시험을 오염시킨다 → **ON·OFF별 전용 Spring 컨텍스트**(`spring.datasource.hikari.connection-init-sql`, `@DirtiesContext(AFTER_CLASS)`로 컨텍스트·풀 폐기)로 격리하고, 서비스 트랜잭션 안에서 `@@session.innodb_snapshot_isolation`을 단언해 설정 적용을 확인(§5-D·§7)
>   - R3-5 수용: `INNODB_LOCK_WAITS` 빈 결과는 비차단의 증거가 아니다 → **A가 PK DELETE를 끝내고 커밋 직전에서 멈춘 채 B의 INSERT·커밋이 완료됨**을 증거로 삼고 대기 테이블은 보조로 쓴다. 관측 장치 검증용 **대조 시험**(범위 DELETE 변형에서 B가 차단됨)을 둔다(§5-F·§7)
>   - 보강 수용: "정상 HTTP 흐름의 연결 1개"는 요청 전체의 연결 수가 아니라 **최대 동시 점유 수 1**(HikariCP MXBean active 최대치)로 시험(§5-F·§7)
> - v4 변경(2라운드 no-ship, codex `gpt-6.1-sol`, 지적 8건 — 전부 수용, 기각 없음. 모두 v3 수정이 만든 문제이거나 1라운드가 놓친 것):
>   - R2-1 수용(코드 확인: 회원 수정이 대상 행을 배타 잠근 뒤 강등·비활성화 시 활성 ADMIN 전체를 추가 배타 잠금 — `AdminMemberService`·`MemberRepository`): 쪽지 INSERT의 FK 공유 잠금이 이 잠금과 맞물려 **S→U→S 교착**이 가능하다. 회원 FK를 두는 한(R1-13의 보존 계약) 어떤 잠금 구조로도 제거되지 않으므로 **계약으로 수용**한다 — 교착 희생자는 409(쪽지 미저장·재시도 안내). 시험을 둘로 분리: 순수 상호 발송(A→B/B→A)은 **409 0건**, 회원 수정과 섞인 경합은 **500 불가·부분 저장 없음·수신자 변경이 검증 전에 커밋됐으면 400, 그 밖에는 201 또는 409**(v5 R3-3으로 정정 — 정상적인 수신자 거부 400을 금지하지 않는다)(§5-F)
>   - R2-2 수용: 이력 정리의 범위 DELETE는 REPEATABLE READ에서 갭·넥스트키 잠금으로 다른 발신자의 INSERT를 막을 수 있다 → 정리를 "만료 id를 일관 읽기로 조회 → **PK 단건 삭제**"로 바꾸고, 발송·삭제 트랜잭션을 **`READ COMMITTED`**로 고정해 갭 잠금 자체를 없앤다. 인접한 발신자 키에서 잠금 대기를 관측하는 시험 추가(§5-F)
>   - R2-3 수용: COUNT가 스냅샷을 연 뒤 수신자 FK 잠금이 오류 1020(`innodb_snapshot_isolation=ON`)을 낼 수 있고 `GlobalApiExceptionHandler`는 `PessimisticLockingFailureException`만 409로 매핑해 500으로 샐 수 있다 → 발송·삭제 트랜잭션을 `READ COMMITTED`로 두어 스냅샷 격리 오류와 낡은 스냅샷(R1-2)을 구조적으로 제거하고, 방어로 **원인 체인의 MariaDB 오류 1020·1213을 409로 매핑**하며 발송도 `innodb_snapshot_isolation` ON·OFF 양쪽에서 시험한다(§5-C·§5-F)
>   - R2-4 수용: F17의 "외부 트랜잭션 참여 금지"는 주석상의 규약일 뿐 REQUIRED는 참여한다(격리 수준도 무시) → 서비스 진입점을 **`@Transactional(propagation = REQUIRES_NEW, isolation = READ_COMMITTED)`**로 두어 호출자의 외부 트랜잭션·스냅샷과 무관하게 항상 새 물리 트랜잭션에서 실행한다. 정상 HTTP 흐름은 컨트롤러가 비트랜잭션이라 연결 1개만 쓰며(풀 고갈 금지 규칙은 로그인 경로 한정), 외부 트랜잭션에서 호출해도 선행 스냅샷이 서비스에 영향을 주지 않음을 시험으로 고정(§5-F·§5-J·§7)
>   - R2-5 수용(코드 확인: 기존 스캐너는 경로 배열의 첫 경로만 읽음): GET/HEAD 전용 컨벤션 시험은 `RequestMappingHandlerMapping`의 **실제 등록 매핑 전체**(모든 경로 별칭·패턴·ANY 메서드)로 검사하고 두 번째 경로 별칭 반례를 둔다. 405 시험은 유효한 CSRF 토큰으로, CSRF 누락 403은 별도 시험(§5-H)
>   - R2-6 수용: 쪽지 제한은 공개 경로 필터·`cms.rate-limit.rules`와 **분리된 컴포넌트**로 확정 — 규칙 ID `message-search`·`message-attempt`, 회원 ID 키, `cms.message.*` 설정, 공개 제한 `enabled`와 독립. 같은 IP의 다른 회원은 독립·같은 회원의 여러 세션은 같은 버킷·공개 제한을 꺼도 D17 유지를 시험(§5-E)
>   - R2-7 수용: 기존 `Bucket`은 "버스트 N + 평균 N/기간"이다 → D17을 **버스트 30 + 평균 분당 30**으로 정정(첫 60초 최대 59건 가능, 명단 수집 계산에 반영). 엄격한 슬라이딩 창은 아님(D17, §5-E, R4)
>   - R2-8 수용: MariaDB 10.11의 변수명은 `@@session.tx_isolation`(`transaction_isolation`은 11.1.1+) → 시험은 서비스가 쓰는 **각 연결**에서 `innodb_snapshot_isolation`을 설정·확인하고 공유 컨테이너 전역 설정을 남기지 않는다. 서버 버전에 변수가 없으면 시험이 그 사실을 기록하고 해당 분기를 건너뜀(§5-D·§7) — **이 "건너뜀" 계약은 R4-6에서 철회됐다(지원 필수 단언)**
>   - 호출 순서 보강 수용: **컨트롤러 인가 → 가드(별도 조회 종료) → 시도 버킷 소비 → 감사 Aspect → 서비스 트랜잭션**으로 고정. 가드 거부와 트랜잭션 전 시도 제한 429는 서비스 감사에 남지 않는다(§5-G)
> - v3 변경(1라운드 no-ship, codex `gpt-6.1-sol`, 지적 13건 + 정찰 정정 7건 — 전부 수용, 기각 없음. 방식이 갈리는 4건은 사용자 결정 D15~D17):
>   - R1-1 수용: 발신자 **회원 행** 잠금은 INSERT의 FK 검사가 상대 회원 행에 거는 공유 잠금과 맞물려 A→B/B→A 동시 발송이 순환 대기(교착)한다 → 잠금은 **회원 행이 아니라 전용 상태 행(`admin_message_sender_state`)**으로 한다(D15, §4·§5-F). 교착이 남을 경우의 409 처리와 A→B/B→A 동시 발송 시험도 계약에 넣는다
>   - R1-2 수용: 잠금 뒤 COUNT라도 잠금 **앞에** 일반 SELECT가 있으면 REPEATABLE READ 스냅샷이 낡아 상한을 넘는다 → "발송 트랜잭션의 **첫 DB 접근은 상태 행 잠금 쓰기**, `now`는 잠금 후 산출, 외부 트랜잭션 참여·선행 조회 금지"를 계약으로 고정하고 선행 스냅샷을 강제하는 시험을 둔다(§5-F)
>   - R1-3 수용: 쪽지 보관함과 발송 이력이 같은 테이블이면 "발송→양쪽 삭제" 자동화로 한도가 회복된다 → 삭제와 무관한 **발송 이력 테이블(`admin_message_send_log`, 발신자·시각만)**을 둔다(D15, §4·§5-F)
>   - R1-4 수용: F13(세션 만료 보장)이 실제보다 강했다 — 세션 만료는 최선 노력이고 인스턴스 로컬이다 → F13 정정, **모든 쪽지 API가 요청마다 본인의 현재 역할·상태를 DB로 확인**(D16, §5-J). 가드는 서비스 트랜잭션 **밖** 별도 읽기 트랜잭션에서 먼저 실행해 R1-2·R1-5의 스냅샷 규칙을 깨지 않는다
>   - R1-5 수용: 삭제 추론은 맞지만 보장 조건이 빠졌다 → "조건부 UPDATE 후 **조건부 DELETE를 네이티브 SQL로 직접 실행**(엔티티·일반 SELECT로 판단 금지)"로 고정, "남더라도 정합성 영향 없음" 문구 삭제, 삭제 트랜잭션의 첫 DB 접근을 UPDATE로 고정(스냅샷 선행 금지 — `innodb_snapshot_isolation=ON`의 오류 1020 방어), 시험은 두 설정값 모두에서 통과(§5-D)
>   - R1-6 수용(방식은 컨벤션 시험): 게이트는 메서드를 구분하지 않아 정확 경로의 모든 메서드를 MANAGER에게 연다 → `SecurityConfig`를 쪼개 인가 정책 변경을 키우는 대신 **`/admin/member/messages`에는 GET/HEAD 핸들러만 허용하는 컨벤션 시험**을 추가한다. 소유권 조건은 괄호까지 명시(§5-D·§5-H)
>   - R1-7 수용: 요청당 결과 제한은 총량 제한이 아니다 → **회원당 분당 30회 인메모리 제한**을 수신자 검색과 발송 시도(수신자 확인 포함)에 각각 적용(D17, §5-E). 단일 인스턴스·fail-open 수준임을 명시
>   - R1-8 수용: 상위 10건만 주면 정확한 아이디도 못 고를 수 있다 → 정확 아이디 일치 우선, `truncated` 표시, 입력 변경 시 선택 해제, 요청 세대 검증(§5-E·§5-I)
>   - R1-9 수용: 검증 순서·유니코드 범위 모호 → 파이프라인(원문 크기 상한 → 잘못된 UTF-16 → 개행 정규화 → 허용 문자 → 최종 길이)과 제목 거부 범주(`Cc`·`Zl`·`Zp`·양방향 제어문자)를 명시, 답장 제목 자르기는 코드포인트 경계 보존(§5-B·§5-I)
>   - R1-10 수용: 감사는 **서비스에 진입한 발송 시도**만 기록(DTO 검증·JSON 파싱 400은 미기록), 실패 감사는 `targetId=null`·`errorMessage` 저장 → 오류 메시지에 사용자 텍스트가 들어가지 않게 고정 문구 사용, 결과 객체는 `Long getRecipientId()` 고정, 액션 라벨은 `admin/log/manage.html`의 `ACTION_TYPE_LABELS`에 추가(§5-G)
>   - R1-11 수용: 삭제 비율이 회원마다 다르다 → 삭제 열을 포함한 복합 인덱스로 바꾸고 통과 기준을 "인덱스 사용"이 아니라 **검사 행 수**로, 시험 데이터에 "최신 10만 건이 한쪽 삭제된 회원"을 넣는다(§4·§7)
>   - R1-12 수용: V21 실패 복구는 **기대 스키마이며 빈 잔여 테이블만** DROP, 데이터가 있으면 백업 후 별도 분기. 롤백 호환은 V21 파일이 실제로 없는 마이그레이션 위치로 시험(§4)
>   - R1-13 수용: FK를 **RESTRICT**로 바꾼다. 알림이 CASCADE를 쓴 이유(회원을 직접 지우는 기존 시험 10곳)는 쪽지를 만들지 않는 그 시험들에는 해당하지 않는다 — 쪽지 시험의 정리만 `TestMembers.delete`가 쪽지·발송 이력·상태 행을 먼저 지우게 한다. D8(각자 보관함)의 보존 계약을 DB가 깨지 못하게 한다(§4·F8)
>   - 정찰 정정 7건 수용: F13 정정, F15 해소(필터는 이미 `ApiErrorResponse`·`RATE_LIMITED`·`Retry-After` 429를 쓰고 서비스 예외용 매핑만 없음 → 신설), §5-E "통합 검색과 같은 규칙" 문구 정정(검색은 최소 2코드포인트·DTO 상한은 raw UTF-16 100이라 다름), §5-G 라벨 위치 정정, DELETE 멱등 표현 정정(최종 효과가 같으면 HTTP 의미상 멱등), F1·F2(게이트는 메서드 비구분)·F7(`@AdminPage`는 인가가 아닌 사이드바 마커) 보강
> - v2: 미결 Q1~Q12 사용자 결정 반영(§1 D3~D14)
> - v1: 초안
>
> 근거: **정적 정찰 기준**(코드를 열어 대조, 빌드·테스트 미실행). 작성 기준 = master `a5a2b2f`(#92 알림까지 머지), 리뷰 대조는 현재 체크아웃 `d37acb2`(#93 메뉴 URL 검증 PR 브랜치 — 쪽지와 겹치는 파일 없음). 브랜치 예정 `feat/admin-message`(master 기준)
> 유형: feat · **스키마 변경**(V21: `admin_message`·`admin_message_sender_state`·`admin_message_send_log`) · **인가 정책 변경 1건**(`MY_INFO` 게이트에 페이지 `/admin/member/messages` 추가 — D3, 2026-10-05 사용자 승인) · 신규 의존성 없음

## 0. 요약

상단바 개선 흐름의 마지막 단계(데모 정리 #88 → 로그아웃·아이콘 #89 → 내 설정 #90 → 통합 검색 #91 → 알림 #92 → **쪽지**). ADMIN·MANAGER가 서로 1:1 쪽지(제목 + 본문)를 주고받는다. 상단바의 비어 있는 봉투 자리(`topbar.html` "쪽지 위젯은 실제 기능과 함께 추가한다")에 받은 쪽지 미읽음 배지와 최근 쪽지 드롭다운을 두고, 전용 **쪽지함 페이지**(받은·보낸 쪽지, 작성, 상세)를 추가한다.

알림(#92)과의 핵심 차이: 알림은 **서버가 상수로 조립한 문장**이지만 쪽지는 **다른 회원이 쓴 임의 입력(제목·본문)**이다. 그래서 길이 상한·저장/표시 XSS·수신자 검증·명단 노출·삭제 의미·발송 빈도 제한·감사 범위가 새 쟁점이다.

## 1. 확정된 사용자 결정

| # | 쟁점 | 결정 |
|---|---|---|
| D1 | 사용 주체 | **ADMIN·MANAGER 모두 주고받는다** |
| D2 | 알림과의 관계 | 별도 기능·별도 테이블. 알림 패턴(§2)을 재사용 |
| D3 | 화면·인가 (Q1) | **전용 쪽지함 페이지 `/admin/member/messages`를 `AdminFeature.MY_INFO`의 `gatePatterns`에 추가**(인가 정책 변경 — 정확 경로 1개 한정, 2026-10-05 승인). 상단바 드롭다운은 최근 받은 쪽지 + 쪽지함 링크. API는 `/admin/api/members/me/**`(기존 `MY_INFO` 게이트 안) |
| D4 | 수신자 선택 (Q2) | **검색 API**(아이디·이름 부분 일치). MANAGER에게 관리자 명단(아이디·이름)이 새로 노출되는 것을 수용 — 노출 필드는 `id`·`userId`·`userName`만 |
| D5 | 수신 가능 상태 (Q3) | `ACTIVE`·`LOCKED`·`PASSWORD_EXPIRED` 허용, `DISABLED`·`DELETED` 거부. 거부 사유는 **한 문구로 통합** |
| D6 | 수신자 수 (Q4) | **1:1만** |
| D7 | 형식 (Q5) | **제목 100자 + 본문 2000자** |
| D8 | 삭제 (Q6) | **각자 보관함에서만 삭제**(상대에게는 남음), 양쪽 모두 삭제하면 물리 삭제. 회수 기능 없음 |
| D9 | 보관 (Q7) | **무기한**(사용자 삭제만, 쪽지 자동 정리 없음) |
| D10 | 읽음 확인 (Q8) | 발신자에게 **읽음 여부·시각 표시** |
| D11 | 빈도 제한 (Q9) | **회원 단위 DB 기반** — 최근 1분 10건·최근 24시간 300건(롤링 창) 초과 시 429 |
| D12 | 감사 (Q10) | **발송만** 감사(수신자 ID), **제목·본문 제외**. 읽음·삭제는 미기록 |
| D13 | 마이그레이션 번호 (Q11) | **쪽지 V21**, 권한 PR B(`role_permission` DROP)는 V22 이상 — `permission/CLAUDE.md` 정정 |
| D14 | 벨 알림 연동 (Q12) | **연동 안 함**(봉투 배지가 별도) — 2026-10-05 확정 |
| D15 | 빈도 제한 구조 (R1-1·3) | **상태 행(잠금용) + 발송 이력 테이블** — D11의 롤링 창을 정확히 유지한다. 쪽지 삭제와 무관 |
| D16 | 발신자 자격 확인 (R1-4) | **모든 쪽지 API가 요청마다 DB로 본인의 현재 역할·상태 확인** |
| D17 | 검색·시도 남용 제한 (R1-7, R2-7) | **회원당 인메모리 토큰 버킷 — 버스트 30 + 평균 분당 30**(검색·발송 시도 각각, 엄격한 슬라이딩 1분 상한 아님), 단일 인스턴스·fail-open 수용 |

## 2. 정찰 사실 표 (재사용할 알림 패턴 포함)

| # | 사실 | 근거 | 이 계획의 처리 |
|---|---|---|---|
| F1 | 알림 API는 `/admin/api/members/me/notifications`로 상시 허용 `MY_INFO` 게이트(`/admin/api/members/me/**`) 안에 있다. 회원 ID는 세션 principal에서만 읽는다(IDOR 방지) | `NotificationController`, `AdminFeature.MY_INFO` | 쪽지 API·수신자 검색 API 모두 `/admin/api/members/me/` 아래 → API 쪽 인가 변경 없음 |
| F2 | `MY_INFO` 게이트 **페이지**는 `/admin/member/info`·`/admin/member/settings`뿐, 그 밖의 `/admin/**`는 ADMIN 캐치올. **ALWAYS 게이트는 HTTP 메서드를 구분하지 않는다**(`SecurityConfig`) | `AdminFeature`, `config/CLAUDE.md` | D3: `gatePatterns`에 `/admin/member/messages`(**정확 경로 1개**) 추가. 메서드 제한은 게이트가 아니라 **컨벤션 시험**으로 보존(§5-H). 하위·유사 경로는 캐치올 — 시험으로 고정 |
| F3 | MANAGER는 현재 회원 목록 API(ADMIN 전용)를 쓸 수 없고 통합 검색의 관리자 섹션도 ADMIN만 | `permission/CLAUDE.md` "통합 검색" | D4로 수신자 검색이 **MANAGER에게 명단을 여는 유일한 경로**가 된다 — 필드·건수·횟수를 제한(§5-E) |
| F4 | 알림 목록은 `beforeId` 커서·size 상한 50, 단건 읽음은 원자적 조건부 UPDATE(`read_at IS NULL`), 없거나 남의 것이면 404 | `notification/CLAUDE.md` | 그대로 재사용 |
| F5 | 상단바 배지는 **페이지 로드 시 1회만**(폴링 없음 — 세션 유휴 타임아웃 연장 방지), 읽음 PATCH는 클라이언트 큐로 직렬화 후 count 재조회 | `topbar-notification.js` | 그대로 재사용 |
| F6 | 화면 렌더링은 `textContent`만, 링크는 같은 출처 경로만 | `topbar-notification.js`, `SafeUrls` | 제목·본문·이름은 `textContent`, 본문은 `white-space: pre-wrap`. 본문 URL 자동 링크화 금지 |
| F7 | `AdminEndpointAuthorizationConventionTest`: `/admin` 아래 API는 인가 선언 필수, 읽기 전용 GET/HEAD 페이지만 면제. **`@AdminPage`는 인가 장치가 아니라 사이드바 모델 주입 마커**(별개 규칙 `AdminPageAnnotationConventionTest`) | `permission/CLAUDE.md`, 루트 `CLAUDE.md` | 쪽지 API 전부 `@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")`. 페이지 컨트롤러는 GET 전용 + `@AdminPage` |
| F8 | 알림 FK가 `ON DELETE CASCADE`인 이유는 "회원을 직접 지우는 기존 통합 시험 10곳이 알림 때문에 깨지지 않게"였다 | V20, PLAN-admin-notification 구현 메모 1 | 그 시험들은 쪽지를 만들지 않으므로 쪽지 FK는 **RESTRICT**(R1-13). 쪽지를 만드는 시험의 정리만 `TestMembers.delete`가 쪽지·이력·상태 행을 먼저 지운다 |
| F9 | 시각은 주입된 KST `Clock`만(`ClockUsageConventionTest`) | 루트 `CLAUDE.md` | 발송·읽음·삭제·빈도 창 계산 모두 `LocalDateTime.now(clock)` — 발송은 잠금 취득 **후** 산출 |
| F10 | 기존 레이트리밋 필터(`cms.rate-limit`)는 IP 키·무인증 공개 경로용(Caffeine, 단일 인스턴스, fail-open). 필터의 429는 `ApiErrorResponse`·`RATE_LIMITED`·`Retry-After` JSON | 루트 `CLAUDE.md`, `RateLimitFilter` | 발송 한도(D11)는 DB, 검색·시도 남용(D17)은 회원 ID 키 인메모리 토큰 버킷. 429 JSON 형식은 필터와 같게 맞춘다 |
| F11 | `@MaxUtf8Bytes`가 고립 서로게이트를 명시 거부하는 선례(Java 기본 인코딩은 잘못된 UTF-16을 조용히 `?`로 바꿈) | `member/CLAUDE.md` | 제목·본문 모두 고립 서로게이트 400 |
| F12 | 권한 PR B(DROP)가 "V21 이상"으로 예약돼 있다(미착수) | `permission/CLAUDE.md` "남은 작업" | D13: 쪽지 V21, PR B 문구를 "V22 이상"으로 정정 |
| F13 | **세션의 역할·상태는 로그인 당시 스냅샷이고, 상태·역할 변경 후 세션 만료는 최선 노력이며 인스턴스 로컬이다**(`AdminSessionService`는 자기 인스턴스의 `SessionRegistry`만 순회). 이미 필터를 통과한 요청도 남는다 | 루트 `CLAUDE.md` "세션 등록·강제 만료", `AdminSessionService` | 낡은 세션이 쪽지를 계속 쓸 수 있다 → D16: 모든 쪽지 API가 요청마다 DB로 현재 역할·상태를 확인(§5-J). 쪽지 한도의 다중 인스턴스 일관성(DB)과 세션 인가의 일관성(DB 확인)을 구분해 둔다 |
| F14 | `@AdminActionLogged`는 `targetIdExpression`(반환 객체 getter — `Long getXxx()`)으로 대상 ID를 뽑고, 감사 저장은 `REQUIRES_NEW`·예외 격리(최선 노력). **실패 시에는 `targetId=null`, `errorMessage=e.getMessage()`**를 저장하고 수신자 ID를 추출하지 않는다. 서비스에 진입한 호출만 기록한다(DTO 검증·JSON 파싱 400은 기록 없음) | `AdminActionLogAspect`, `com.cms.admin.log` `CLAUDE.md` | §5-G |
| F15 | 필터는 이미 `ApiErrorResponse`·`RATE_LIMITED`·JSON 429·`Retry-After`를 쓴다. **서비스 예외용 429 매핑은 없다** | `RateLimitFilter`, `GlobalApiExceptionHandler` | `RateLimitedException`(서비스 예외) + 핸들러를 신설해 같은 형식으로 응답(§5-F) |
| F16 | `GlobalApiExceptionHandler`가 교착(데드락)을 409로 처리한다 | `GlobalApiExceptionHandler`(:284) | 쪽지 발송·삭제의 교착은 409로 나오며 화면이 "다시 시도" 안내를 한다(§5-F) |
| F17 | `MemberPermissionService.replace`는 첫 DB 조회가 회원 행 `SELECT … FOR UPDATE`인 **관례**를 따르지만 기본 `@Transactional`(REQUIRED)이라 외부 트랜잭션에 참여한다 — 호출 경계에서 막지 않는다 | `MemberPermissionService`(:79·:88) | 쪽지 서비스는 관례에 기대지 않고 **`REQUIRES_NEW`+`READ_COMMITTED`**로 호출 경계에서 보장한다(§5-F, R2-4) |
| F18 | 회원 수정(`AdminMemberService.updateAdminMember`)은 대상 행을 배타 잠근 뒤 강등·비활성화 시 **활성 ADMIN 전체를 배타 잠금**한다(최후 ADMIN 가드) | `AdminMemberService`(:203), `MemberRepository`(:89) | 쪽지 INSERT의 FK 공유 잠금과 교착 가능 → 409 수용(§5-F, R2-1) |
| F19 | 기존 컨벤션 스캐너는 클래스·메서드 경로 배열의 **첫 경로만** 읽는다. `RateLimitFilter`는 공개 경로 규칙 목록으로 IP 버킷을 소비하고 `Bucket`은 "버스트 N + 평균 N/기간"이다 | `AdminEndpointAuthorizationConventionTest`(:211), `RateLimitFilter`, `Bucket` | 신규 GET/HEAD 컨벤션은 등록 매핑 전체로 검사, 쪽지 제한은 필터와 분리(§5-E·§5-H) |

## 3. 범위

**포함**
- V21 `admin_message`·`admin_message_sender_state`·`admin_message_send_log`, 엔티티·리포지토리(QueryDSL)·서비스
- API: 보내기·받은/보낸 목록·단건 조회·읽음·미읽음 수·삭제(본인 쪽)·수신자 검색
- 인가: `MY_INFO` 게이트에 `/admin/member/messages` 1개 추가(D3) + GET/HEAD 전용 컨벤션 시험
- 화면: 상단바 봉투 + 배지 + 드롭다운, 쪽지함 페이지(받은/보낸 탭·상세·작성·답장·삭제)
- 발송 빈도 제한(D11·D15), 검색·시도 제한(D17), 현재 자격 확인(D16), 발송 감사(D12)
- 공용 감사 모듈 소규모 확장: `@AdminActionLogged`에 선택 속성 `safeErrorMessage`(고정 오류 문구, R3-1) — `MESSAGE_SEND`만 사용
- 문서: `com.cms.admin.message` `CLAUDE.md` 신설, 루트 "지침 파일 지도", `config/CLAUDE.md` 접근 제어 표(승인 이력), `permission/CLAUDE.md`(카탈로그 표·PR B 번호), **`com.cms.admin.log` `CLAUDE.md`(`safeErrorMessage`)**, `docs/migration-guide.md` "V21 실패 복구", **`docs/deployment.md`(JDBC 진단 옵션·문자셋·strict mode 점검 항목, R5-1)**

**제외**
- 실시간 푸시·폴링·이메일 전달, 벨 알림 연동(D14)
- 첨부파일·서식(HTML·마크다운)·본문 자동 링크화
- 대화 스레드(답장 = 수신자·`Re: 제목`을 미리 채운 새 쪽지)
- 다중 수신자(D6)·회수(D8)·쪽지 자동 정리(D9)
- ADMIN의 타인 쪽지 열람·감독(본인 것만 — §5-D)
- 사이드바 메뉴 시드(상단바·쪽지함 링크로 진입)

## 4. 스키마 (V21)

```sql
CREATE TABLE `admin_message` (
  `id`                    bigint(20)    NOT NULL AUTO_INCREMENT,
  `sender_id`             bigint(20)    NOT NULL,
  `recipient_id`          bigint(20)    NOT NULL,
  `title`                 varchar(100)  NOT NULL,
  `body`                  varchar(2000) NOT NULL,
  `read_at`               datetime(6)   DEFAULT NULL,
  `sender_deleted_at`     datetime(6)   DEFAULT NULL,
  `recipient_deleted_at`  datetime(6)   DEFAULT NULL,
  `create_date`           datetime(6)   NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_admin_message_inbox`  (`recipient_id`, `recipient_deleted_at`, `id`),             -- 받은 목록 커서(삭제 열 포함, R1-11)
  KEY `idx_admin_message_unread` (`recipient_id`, `recipient_deleted_at`, `read_at`, `id`),  -- 미읽음 수
  KEY `idx_admin_message_sent`   (`sender_id`, `sender_deleted_at`, `id`),                   -- 보낸 목록 커서
  CONSTRAINT `fk_admin_message_sender`    FOREIGN KEY (`sender_id`)    REFERENCES `member` (`id`),
  CONSTRAINT `fk_admin_message_recipient` FOREIGN KEY (`recipient_id`) REFERENCES `member` (`id`),
  CONSTRAINT `ck_admin_message_not_self`  CHECK (`sender_id` <> `recipient_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `admin_message_sender_state` (       -- 발신자별 잠금 전용 행(내용 없음) — 회원 행을 잠그지 않기 위한 뮤텍스(R1-1)
  `member_id` bigint(20) NOT NULL,
  PRIMARY KEY (`member_id`),
  CONSTRAINT `fk_admin_message_state_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `admin_message_send_log` (           -- 발송 이력(삭제와 무관한 한도 계산용, 발신자·시각만 — R1-3)
  `id`        bigint(20)  NOT NULL AUTO_INCREMENT,
  `sender_id` bigint(20)  NOT NULL,
  `sent_at`   datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_admin_message_send_log` (`sender_id`, `sent_at`),
  CONSTRAINT `fk_admin_message_send_log_member` FOREIGN KEY (`sender_id`) REFERENCES `member` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
```

- **FK는 모두 RESTRICT**(R1-13, F8): 회원 행을 직접 지우는 SQL은 쪽지·이력·상태 행이 있으면 실패한다 — 상대 보관함의 쪽지가 조용히 사라지지 않는다. 앱은 회원을 하드 삭제하지 않는다(`DELETED` 전이). 쪽지를 만드는 시험의 정리는 `TestMembers.delete`가 `admin_message`·`admin_message_send_log`·`admin_message_sender_state` 순으로 지운다
- 테이블 이름 `admin_message`(범용 `message` 혼동 회피). 행 크기 `varchar(100)+varchar(2000)` utf8mb4 최대 8,400바이트 — InnoDB 행 한도 안
- **인덱스(R1-11)**: 삭제 열을 인덱스에 포함해 "삭제된 행을 건너뛰며 읽기"를 줄인다. 그래도 한쪽만 삭제된 행이 인덱스 범위 안에 남으므로 **통과 기준은 "인덱스 사용"이 아니라 첫 페이지·`beforeId` 페이지·미읽음 수 SQL의 검사 행 수**(§7)로 한다. 후보 비교: 위 3개 vs 삭제 열 제외 vs 부분 인덱스 없음(MariaDB) — 구현 시 실측으로 확정
- **발송 이력**: 발송 트랜잭션이 본인의 24시간 지난 행을 같은 트랜잭션에서 삭제한다(스케줄러 없음) — **범위 DELETE가 아니라 만료 id를 조회한 뒤 PK 단건 삭제**(`(sender_id, sent_at)` 인덱스의 범위 DELETE는 갭·넥스트키 잠금으로 인접한 다른 발신자의 INSERT를 막을 수 있다, R2-2). 마지막 발송 이후 로그인하지 않거나 발송하지 않는 회원의 이력은 남는다(회원당 최대 300+행, 제목·본문 없음 — 한계로 기록). 이력은 한도 판정의 권위이고 감사 로그를 쓰지 않는다
- **상태 행**: 발송 첫 문장이 `INSERT … ON DUPLICATE KEY UPDATE member_id = member_id`로 행을 만들며 동시에 잠근다(§5-F). V21은 행을 미리 채우지 않는다
- 물리 삭제(D8): 두 번째 쪽 삭제 시 같은 트랜잭션의 **네이티브 조건부 DELETE**(§5-D)
- **V21 실패 복구(R1-12)**: V20과 달리 **데이터 유무를 확인한다**. 절차(`docs/migration-guide.md`에 추가): 앱 정지 → `flyway_schema_history`·실제 테이블 정의(기대 스키마와 일치)·세 테이블의 행 수 확인 → **성공 이력이 없고, 기대 스키마이며, 세 테이블이 모두 비어 있을 때만** 잔여 테이블을 DROP하고 `flyway repair` → 재기동. 데이터가 있거나 정의가 다르면 DROP하지 않고 백업 후 별도 분기(이력·데이터 시점이 어긋난 복원). 시험: 정상 성공 이력 / 빈 잔여 객체 복구 / **데이터가 든 이력 불일치는 DROP하지 않음** 3분기
- **앱 롤백 호환**: 이전 앱은 테이블을 모른다(`ddl-auto: validate`는 매핑된 테이블만). 이전 앱의 Flyway가 적용된 미지의 V21을 무시하는지는 **V21 파일이 실제로 없는 마이그레이션 위치**(예: V21을 제외한 클래스패스 디렉터리)로 기동해 확인한다 — `target("20")` 설정만으로는 V21 파일이 여전히 해석 대상이라 증명이 아니다. Flyway 기본값 `ignoreMigrationPatterns=*:future`가 실제 설정에서 유지되는지도 확인. 롤백 시 `MY_INFO` 게이트도 이전으로 돌아가 쪽지함 페이지는 MANAGER에게 403(데이터는 남음)

## 5. 설계

### 5-A. 패키지

`com.cms.admin.message` — `domain/AdminMessage`, `repository/AdminMessageRepository(+Custom/Impl, QueryDSL + 네이티브 DML)`, `repository/SenderState·SendLog 리포지토리`, `service/AdminMessageService`, `service/MessageActorGuard`(§5-J), `service/MessageTextPolicy`(§5-B), `controller/AdminMessageController`(API), `controller/AdminMessagePageController`(`@AdminPage`, GET 전용), `dto/*`

### 5-B. 입력 규칙 (사용자 입력)

- 평문만. 서버는 **원문 그대로 저장**하고 이스케이프·필터링하지 않는다(표시 계층 책임). 화면은 `textContent` + `white-space: pre-wrap`만 사용
- **검증 파이프라인(R1-9, 이 순서 고정, `MessageTextPolicy`)**:
  1. 원문 크기 방어 — DTO `@Size`: 제목 raw ≤ 200, 본문 raw ≤ 4000 UTF-16 단위(정규화 전 입력의 자원 방어. 개행 정규화는 길이를 줄이기만 하므로 정상 입력을 거부하지 않는다)
  2. 잘못된 UTF-16(고립 서로게이트) 거부(F11)
  3. 개행 정규화 — 본문의 `\r\n`·`\r` → `\n`(제목은 개행 자체를 거부하므로 정규화 없음)
  4. 허용 문자 검사 — 제목: `Cc`(제어문자 전부, 개행·탭 포함)·`Zl`(U+2028)·`Zp`(U+2029)·**양방향 제어문자**(U+202A~202E, U+2066~2069 — 표시 순서 위조 방지) 거부. 본문: `Cc` 중 `\n`·`\t` 외, `Zl`·`Zp`, 양방향 제어문자 거부. **ZWJ(U+200D) 등 일반 `Cf`는 허용**(이모지 합성 시퀀스가 깨지지 않게). 제목 앞뒤 공백 trim
  5. 최종 길이 — 제목 ≤ 100, 본문 ≤ 2000 UTF-16 단위, `@NotBlank` 상당(정규화·trim 후 비어 있으면 거부)
- 실패는 400이며 **오류 메시지는 고정 한국어 문구**(사용자 입력을 메시지에 넣지 않는다 — 감사 `errorMessage` 오염 방지, R1-10)
- 서버 로그·감사에 제목·본문을 남기지 않는다. 화면의 남은 글자 수도 UTF-16 길이(`value.length`)로 센다(서버 기준과 일치)

### 5-C. 수신자 검증 (보내기)

- 요청은 `recipientId`(수신자 검색 결과의 회원 ID)
- 거부 조건 — 전부 **같은 400 문구** "쪽지를 받을 수 없는 수신자입니다.": 없음, `ROLE_USER`, 자기 자신, `DISABLED`·`DELETED`(D5)
- 수신자 행을 애플리케이션이 잠그지 않는다. **단 INSERT의 FK 검사가 수신자·발신자 회원 행에 공유 잠금을 건다**(R1-1) — 그 행을 다른 트랜잭션이 수정 중이면 저장이 대기한다. 공유 잠금끼리는 충돌하지 않으므로 **순수 상호 발송(A→B/B→A)은 교착하지 않는다**(상태 행은 발신자별로 다르다). 다만 **회원 수정(강등·비활성화의 활성 ADMIN 전체 배타 잠금 — F18)과는 교착할 수 있다**(R2-1, §5-F)
- 검사와 INSERT 사이에 수신자가 `DELETED`로 바뀌는 경합: 트랜잭션이 `READ COMMITTED`이므로(§5-F) 수신자 검증은 직전 커밋 값을 보고, FK는 행 존재만 보장하므로 검증 직후 전이가 커밋돼도 INSERT가 성공해 삭제 계정 보관함에 쪽지가 남는다 — 그 계정은 로그인할 수 없어 실해가 없다(한계). 스냅샷 격리 설정(`innodb_snapshot_isolation`)에 따라 결과가 달라지지 않는다(RC 고정)
- 회원이 하드 삭제되는 경합: RESTRICT라 회원 행 삭제 쪽이 쪽지 INSERT의 공유 잠금과 충돌해 대기하거나 FK 위반으로 실패한다(운영 절차상 하드 삭제 없음)
- 발신자 자격은 §5-J(DB 현재 확인)

### 5-D. API (`/admin/api/members/me/messages`, 전부 `hasAnyRole('ADMIN','MANAGER')` + §5-J 가드)

| 메서드·경로 | 설명 | 응답 |
|---|---|---|
| `POST …/messages` `{recipientId, title, body}` | 보내기. 400(검증·수신자)·429(시도 한도·발송 한도)·409(교착 — 재시도 안내) | 201 + `Location` + 항목 |
| `GET …/messages?box=inbox\|sent&size=20&beforeId=` | 받은/보낸 목록(id 내림차순 커서, size 상한 50, 본인 쪽 삭제 제외). `box` 누락·이상 값 400 | `{content:[{id, counterpart:{id,userId,userName}, title, read, readAt, createDate}], hasMore}` — 본문 미포함. 보낸 목록의 `read`·`readAt`이 읽음 확인(D10) |
| `GET …/messages/{id}` | 단건(제목·본문 전체). 소유 조건을 만족할 때만, 아니면 404. **읽음을 일으키지 않는다** | 항목 + `body` + `direction`(`RECEIVED`/`SENT`) |
| `PATCH …/messages/{id}` `{"read": true}` | **수신자만** 읽음 — 원자적 조건부 UPDATE, 0행이면 존재 확인 후 이미 읽음 200(기존 시각 유지)·그 외 404. `read:false` 400 | 200 + `unreadCount` |
| `GET …/messages/unread-count` | 배지용 | `{unreadCount}` |
| `DELETE …/messages/{id}` | **본인 쪽에서만 삭제**(D8) | 204, 없거나 남의 것·이미 지운 것 404 |
| `GET …/message-recipients?keyword=` | 수신자 검색(D4·§5-E) | `{content:[{id,userId,userName}], truncated}` |

- **소유 조건(R1-6)은 괄호까지 고정한다**: `(sender_id = :me AND sender_deleted_at IS NULL) OR (recipient_id = :me AND recipient_deleted_at IS NULL)` — 단건·삭제. 읽음은 `recipient_id = :me AND recipient_deleted_at IS NULL AND read_at IS NULL`. 경로에 회원 ID를 받지 않는다
- **삭제 알고리즘(R1-5) — 아래 순서·SQL 종류를 고정한다**:
  1. 서비스 진입점은 **`@Transactional(propagation = REQUIRES_NEW, isolation = READ_COMMITTED)`**(§5-F와 같은 이유 — 호출자의 외부 트랜잭션·스냅샷과 무관하게 항상 새 물리 트랜잭션, 격리 수준 반드시 적용). **첫 DB 접근이 조건부 UPDATE**여야 한다(일반 SELECT·엔티티 로딩 선행 금지 — 자격 가드는 §5-J대로 **서비스 트랜잭션 밖**에서 먼저 실행). RC에서는 `innodb_snapshot_isolation`의 오류 1020이 적용되지 않고 UPDATE·DELETE가 항상 최신 커밋 값을 현재 읽기한다
  2. `UPDATE admin_message SET sender_deleted_at = :now WHERE id = :id AND sender_id = :me AND sender_deleted_at IS NULL` → 1행이면 발신측 성공. 0행이면 `UPDATE … SET recipient_deleted_at = :now WHERE id = :id AND recipient_id = :me AND recipient_deleted_at IS NULL`. 둘 다 0행이면 404(없음·남의 것·이미 지운 것). 자기 자신에게 보낸 쪽지는 CHECK로 불가능하므로 한 회원이 양쪽이 될 수 없다
  3. **네이티브 조건부 DELETE를 직접 실행**: `DELETE FROM admin_message WHERE id = :id AND sender_deleted_at IS NOT NULL AND recipient_deleted_at IS NOT NULL`(`@Modifying(clearAutomatically = true)`). **물리 삭제 여부를 일반 SELECT·엔티티 플래그로 판단하지 않는다**(낡은 값으로 DELETE를 생략할 수 있다). 엔티티 수정과 벌크 DML을 섞지 않는다
  - **보장**: 두 쪽이 동시에 지우면 먼저 UPDATE한 쪽이 행 잠금을 쥐고 DELETE(수신측 열이 아직 NULL이라 0행)·커밋하고, 대기하던 쪽이 UPDATE를 마친 뒤 DELETE에서 현재 커밋된 양쪽 값을 읽어 1행을 지운다 — **둘 다 성공하면 DELETE 영향 행 합계 1, 최종 행 부재**. (일반 SELECT 기반 판단은 이 보장이 없다)
  - 시험(§7)은 `@@version`·**`@@session.tx_isolation`**(MariaDB 10.11의 변수명 — `transaction_isolation`은 11.1.1+라 `Unknown system variable`로 실패한다, R2-8)을 기록하고, **서비스 작업이 실제로 쓰는 연결에서** `innodb_snapshot_isolation`이 ON·OFF인 두 경우 모두에서 양쪽 UPDATE 성공·DELETE 합계 1·최종 행 부재를 검증한다. **설정 격리(R3-4)**: `SET SESSION innodb_snapshot_isolation`은 풀의 물리 연결에 남고 HikariCP는 이 임의 세션 변수를 복원하지 않으므로, 공유 Spring 컨텍스트의 연결에 설정하지 않는다. 대신 **ON·OFF별 전용 Spring 컨텍스트**(시험 클래스를 나누어 `spring.datasource.hikari.connection-init-sql=SET SESSION innodb_snapshot_isolation=ON|OFF`, `@DirtiesContext(AFTER_CLASS)`로 컨텍스트와 풀을 폐기)에서 실행하고, 서비스 트랜잭션 안에서 `@@session.innodb_snapshot_isolation`을 읽어 설정이 **실제로 적용됐음**을 단언한다. 전역 설정은 건드리지 않는다. **지원은 필수다(R4-6)**: 변수가 없는 서버에서는 `connection-init-sql`에서 Hikari·Spring 컨텍스트 기동이 먼저 실패해 "건너뛰기"에 도달하지 못하므로 건너뛰기 계약은 두지 않는다. 시험 컨테이너는 이미지 digest로 고정돼 있으므로 단계 1에서 고정 이미지의 `SELECT VERSION()`과 변수 존재를 확인해 시험의 사전 조건으로 단언하고, **없으면 지원 버전(10.11.9+)으로의 시험 이미지 변경을 사용자 결정으로 올린다**(digest 고정 관례와 CI 배포 게이트의 `scripts/ci/check-image-refs.sh`가 운영 이미지 전용인지 테스트 이미지에도 걸리는지 단계 1에서 대조). 원문 "남더라도 정합성 영향 없음"은 철회 — 잔여 행은 D8의 물리 삭제 약속 위반이다
  - 삭제는 HTTP 의미상 멱등이다(최종 효과가 같다). 이미 지운 쪽의 반복 요청이 404인 것은 존재 숨김 정책이다
- 읽음과 수신자 삭제 경합: 같은 행 조건부 UPDATE로 직렬화. 삭제된 뒤 읽음 PATCH는 404
- 상대 정보(`counterpart`)는 현재 `member` 행에서 조인 — 상대가 `DELETED`여도 쪽지는 보인다. 이름은 현재 값. 이메일·역할·상태는 노출하지 않는다
- `preview`는 두지 않는다(목록은 제목만) — 본문은 단건 조회에서만 나간다

### 5-E. 수신자 검색 (D4·D17)

- **검색 원문을 보존한다(R6-1)**: 서버는 `keyword`를 trim하지 않은 **원문**으로 받는다(길이 상한 초과는 400 — 원문의 코드포인트 수가 `member.userId` 기존 길이 범위 50을 넘으면 정확 일치 조회를 하지 않고, 부분 검색 상한도 50코드포인트). 회원 생성 API는 아이디 앞뒤 공백을 막지도 정규화하지도 않으므로(`AdminSignupRequest`는 `@NotBlank`·최대 길이만 검사하고 `AdminMemberService`는 그대로 저장) 앞뒤 공백이 든 아이디가 정상 데이터일 수 있다
- **조회 순서**: ① **원문 그대로의 `userId` 정확 일치**를 먼저 조회한다(trim·소문자화 없이 — DB 콜레이션의 비교 규칙을 따른다). 원문이 공백만이거나 비어 있으면 빈 결과 200 ② **부분 검색은 원문을 trim한 뒤 2~50코드포인트**일 때만 한다(통합 검색의 최소 길이와 같지만 **상한 규칙은 다르다** — 통합 검색 DTO는 raw UTF-16 100, R1 정정). `LIKE` 이스케이프(`%`·`_`·`\` 리터럴 — 통합 검색 구현 대조). 두 조회는 **같은 수신 가능 필터·자기 제외·D17 제한**을 적용하고, 결과는 정확 일치를 앞에 두고 **회원 `id`로 중복을 제거**한다(`member.user_id`는 `utf8mb4_general_ci` 유일 제약이라 원문 정확 일치는 최대 1건 — V1). **검색 버킷(D17)은 요청당 1회만 소비**한다(정확 일치와 부분 검색 두 조회가 각각 소비하지 않는다)
- **1코드포인트 입력(R5-2, R6-1)**: 한 글자 아이디·이름도 정상 계정이다. trim 후 1코드포인트이면 부분 검색은 하지 않고 **①의 원문 정확 일치만** 한다. **보장 문구(정확한 범위)**: 수신 가능한 어떤 계정도 **저장된 아이디를 원문 그대로 입력하면 항상 선택할 수 있다**(이름이 한 글자인 계정도 아이디로 닿는다). 앞뒤 공백이 든 아이디는 그 공백까지 입력해야 정확 일치로 닿으며, 그 아이디를 포함하는 2코드포인트 이상의 부분 검색어(trim 후)로도 닿는다
- **열거 한계의 사실 기록(R6 참고)**: 1코드포인트 정확 조회는 수신 가능 계정의 **존재 확인 경로**다. D17은 이를 **차단하지 않고 속도를 제한**한다 — 대소문자를 구분하지 않는 영문·숫자 36개 후보는 초기 30토큰과 리필로 약 12초에 순회할 수 있다. 수신 가능 집합의 노출은 D4가 이미 수용한 범위이며 별도 보안 결함으로 보지 않는다
- 대상: `ROLE_ADMIN`·`ROLE_MANAGER` 중 `ACTIVE`·`LOCKED`·`PASSWORD_EXPIRED`(D5), **자기 자신 제외**
- **정렬·잘림(R1-8)**: `userId` 정확 일치(대소문자 무시 — DB 콜레이션)를 **먼저**, 나머지는 `userName`, `id` 순. 11건을 읽어 10건만 반환하고 11번째가 있으면 `truncated: true`. 화면은 "결과가 더 있습니다. 검색어를 좁혀주세요"를 표시
- 노출: `id`·`userId`·`userName`만. 역할·상태·이메일 없음(다만 수신 가능 집합 자체가 드러나므로 비활성 여부가 간접 추론될 수 있다 — D4 수용 범위)
- **총량 제한(R1-7, D17, R2-6·7)**: 인증 회원 ID 키의 **인메모리 토큰 버킷 — 버스트 30 + 평균 분당 30**을 검색에 적용한다. 발송 시도(수신자 확인 포함)에는 **별도 버킷**(같은 수치)을 `POST` 진입 시(트랜잭션 전)에 소비한다 — 수신자 검증 400과 201의 차이로 수신 가능 계정을 훑는 경로(요청 수 제한 없이 `recipientId`를 순차 시도)도 이 제한을 받는다.
  - **계약 정정(R2-7)**: 이 버킷은 시작 시 토큰 30개 + 연속 리필이라 "임의의 1분 동안 30회 이하"를 보장하지 않는다 — 시작 즉시 30회 후 2초마다 1회면 첫 60초에 최대 **59회**가 허용된다. 명단 수집 가능량도 이 값으로 계산한다. 엄격한 슬라이딩 1분 상한은 이 계획의 범위가 아니다
  - **공개 필터와 분리(R2-6)**: 쪽지 제한은 `cms.rate-limit.rules`·`RateLimitFilter`에 규칙을 추가하지 **않는다**(필터는 IP 키로 소비하므로 같은 사무실 IP의 여러 회원이 서로를 차단하는, 승인되지 않은 IP 제한이 생긴다). **분리된 컴포넌트**(`MessageRateLimiter`)가 규칙 ID `message-search`·`message-attempt`를 회원 ID 키로 소비하고, 설정(`cms.message.*`)·검증·활성화는 공개 제한의 `cms.rate-limit.enabled`와 **독립**이다(공개 제한을 꺼도 D17은 유지). 기존 `Bucket`(토큰 버킷 알고리즘) 클래스 재사용 가능 여부와 Caffeine 캐시(`maximumSize`+`Expiry`) 구성은 단계 1에서 대조한다. 시험: 같은 IP의 서로 다른 회원은 독립, 여러 세션의 같은 회원은 같은 버킷, 공개 제한 비활성화에도 D17 유지
  - **수준 명시**: 단일 인스턴스·fail-open(캐시 포화 시 정확성 흐트러짐)을 수용 — 다중 인스턴스 정확 제한은 하지 않는다. 429는 필터와 같은 JSON(`RATE_LIMITED`, `Retry-After`)
- 설정: `cms.message.search-per-minute`·`attempt-per-minute`(기본 30 — 용량과 분당 리필 수), 0 이하·누락은 기동 실패(`RateLimitConfigValidator` 관례)
- 비용 측정: 검색 쿼리의 검사 행 수를 시험에서 기록한다(`%keyword%`라 인덱스를 못 쓸 수 있음 — 관리자·매니저 행 수는 소수라는 전제를 문서화)

### 5-F. 발송 빈도 제한 (D11·D15) — 상태 행 잠금 + 발송 이력

**트랜잭션 계약(R1-2·R2-3·R2-4)**: 발송 서비스 진입점은 **`@Transactional(propagation = REQUIRES_NEW, isolation = READ_COMMITTED)`**다. ① `REQUIRES_NEW`는 호출자에게 외부 트랜잭션·일관 읽기 스냅샷이 있어도 **항상 새 물리 트랜잭션**으로 실행한다(REQUIRED는 참여하며 격리 수준도 무시한다). 정상 HTTP 흐름은 컨트롤러가 비트랜잭션(`open-in-view: false`)이라 가드·서비스·감사가 **순차**로 연결을 쓰며 **최대 동시 점유는 1개**다(요청 전체에서 쓰는 연결의 개수가 아니라 동시에 점유하는 수 — 시험은 HikariCP MXBean의 active 연결 최대치로 측정). 풀 고갈 방지 규칙(로그인 경로 `REQUIRES_NEW` 금지)은 로그인 경로 한정이라 해당 없다 ② `READ_COMMITTED`는 **일관 읽기가 문장마다 최신 커밋을 보게** 하여 낡은 스냅샷(R1-2)을 없애고, `innodb_snapshot_isolation=ON`의 오류 1020(스냅샷 이후 수정된 행을 잠글 때)이 발생하지 않게 하며, 갭·넥스트키 잠금을 줄인다(R2-2·R2-3). Spring이 격리 수준을 실제로 적용하는지(`JpaTransactionManager`+`HibernateJpaDialect`)는 **시험이 트랜잭션 안에서 `@@session.tx_isolation = 'READ-COMMITTED'`를 단언**해 확인한다

**발송 트랜잭션 순서(고정, R1-1·2·3)**:
1. (트랜잭션 밖) 컨트롤러 인가 → §5-J 가드(별도 조회, 종료) → D17 시도 버킷 소비 → 감사 Aspect → 서비스 진입(§5-G)
2. 새 트랜잭션(RC). **첫 DB 접근**: `INSERT INTO admin_message_sender_state (member_id) VALUES (:me) ON DUPLICATE KEY UPDATE member_id = member_id` — 쓰기 문장이라 행이 없으면 만들고 있으면 배타 잠금을 얻는다. 이 앞에 일반 SELECT·엔티티 로딩을 두지 않는다(RC라 필수는 아니지만 잠금을 먼저 쥐는 규약을 유지). 최초 생성 경합(같은 회원의 동시 첫 발송)은 InnoDB가 중복 키 대기로 직렬화하며 드물게 교착 희생자가 나올 수 있다 → 409(아래)
3. `now = LocalDateTime.now(clock)` — **잠금 취득 후** 산출(F9)
4. 이력 집계(RC 일관 읽기 — 직전 발송이 커밋돼 있으면 반드시 보인다): `COUNT(*) FROM admin_message_send_log WHERE sender_id = :me AND sent_at > :now − 1분`(≥10이면 429), `… > :now − 24시간`(≥300이면 429). 인덱스 `(sender_id, sent_at)`
5. 수신자 검증(§5-C, 같은 RC 읽기)
6. `INSERT admin_message` + `INSERT admin_message_send_log(:me, :now)` + 본인의 만료 이력 정리(**만료 id 조회 → PK 단건 DELETE**, 범위 DELETE 금지 — R2-2)
7. 커밋
- **교착 분석(R1-1·R2-1)**: 상태 행은 발신자마다 다르다. A→B와 B→A는 서로 다른 상태 행(X)을 잡고 `admin_message` INSERT의 FK 검사가 양쪽 회원 행에 **공유** 잠금을 건다 — 공유끼리 충돌하지 않으므로 **순수 상호 발송은 순환 대기가 없다**. **그러나 회원 수정과는 교착할 수 있다**(F18): 회원 수정은 대상 회원 행을 배타 잠근 뒤 강등·비활성화 시 활성 ADMIN 전체를 배타 잠근다. 예) 활성 ADMIN A가 B에게 발송 중(`member(A)` S 보유, `member(B)` S 대기) ↔ 다른 관리자가 B를 비활성화(`member(B)` X 보유, 활성 ADMIN 전체 잠금에서 `member(A)` X 대기) → **S→U→S 순환**. 쪽지 INSERT의 FK 검사가 있는 한 어떤 잠금 구조(상태 행 FK 제거 포함)로도 제거되지 않고, FK를 빼면 R1-13의 보존 계약을 잃으므로 **계약으로 수용**한다: 교착 희생자는 `GlobalApiExceptionHandler`가 **409**로 응답하고(F16) — 쪽지는 저장되지 않으며(발송 트랜잭션 전체 롤백) 화면은 "다시 시도" 안내를 한다(자동 재시도 없음). 희생자가 회원 수정 쪽이 될 수도 있다(그쪽의 기존 409 처리가 따른다)
- **409 매핑의 범위(R2-3)**: 현재 `GlobalApiExceptionHandler`는 `PessimisticLockingFailureException`만 409로 처리한다. 방어로 **원인 체인의 MariaDB 오류 1020·1213**도 409로 매핑하는 핸들러 보강을 단계 1에 포함한다(RC에서는 1020이 나오지 않아야 하지만 매핑 누락이 500으로 이어지지 않게 한다). 단위 시험: 원인 체인에 `SQLException(errorCode=1020/1213)`을 가진 예외 → 409
- **시험 계약 분리(R2-1)**: ① **순수 상호 발송**(A→B와 B→A 동시, 다른 변경 없음)은 **둘 다 201, 409 0건**을 요구한다("임의의 409도 성공"으로 인정하지 않는다) ② **회원 수정이 섞인 경합**은 아래 **조건표 하나**로 단언한다(R3-3·R4-4·R4-5 — 개정 이력·§7도 이 표를 따른다). 어느 행이든 공통 불변식은 **500 없음·부분 저장 없음(쪽지와 이력이 함께 저장되거나 함께 없다)·한도 정확**이다.

| 수신자에게 일어난 변경과 시점 | 기대 결과 |
|---|---|
| **검증 전에 커밋된 `DISABLED`**(관리자가 상태를 `DISABLED`로 변경) | RC 조회가 새 상태를 읽어 **고정 문구 400**, 쪽지·이력 미저장, FAIL 감사 1건 |
| **검증 전에 커밋된 ADMIN→MANAGER 강등** | 수신자 검증 **통과**(D5 — 역할은 수신 가능 집합 안), 발송 **201** |
| **검증 후(INSERT 전) 커밋된 상태 변경**(`DISABLED` 등) | 발송 **201**(비활성 계정 보관함에 남는 한계, §5-C — 검증이 이미 끝남) |
| **`ROLE_USER` 상태의 수신자** | 회원 수정 API는 `ROLE_ADMIN`·`ROLE_MANAGER`만 받으므로(`AdminMemberUpdateRequest`) API로는 만들 수 없다 — **SQL fixture로만** 방어 시험(고정 문구 400) |
| **실제 교착 희생자가 발송**(S→U→S, §5-F 교착 분석) | 발송 **409**(쪽지·이력 함께 미저장) |
| **실제 교착 희생자가 회원 수정** | 회원 수정의 기존 409 처리가 따른다(발송은 영향 없음) |

- **시험 배치(R4-4)**: "검증 전/후" 순서 시험은 **수신자 스칼라 조회가 반환된 직후·쪽지 INSERT 이전**에 시험용 advice로 발송 트랜잭션을 멈춘다. **발신자는 MANAGER**로 두고 수정 실행자는 **별도 ADMIN**으로 구성한다 — ADMIN 발신자라면 상태 행 INSERT의 FK 공유 잠금이 `member(발신자)`를 쥐고 있어, 다른 활성 ADMIN을 비활성화하는 수정(활성 ADMIN 전체 배타 잠금)이 그 행에서 대기해 시험이 멈춘 지점에서 진행하지 못한다(`beforeCommit`에서 멈추는 것도 같은 이유 — 이미 쪽지 INSERT가 수신자 회원 행의 공유 잠금까지 잡았다). **실제 ADMIN↔ADMIN 교착 시험은 이 순서 시험과 분리**해 별도로 구성한다. 교착을 결정적으로 재현하기 어려우면 `INNODB_LOCK_WAITS`를 **보조 증거**로 쓴다
- **인접 발신자 시험(R2-2, R3-5)**: 이력 인덱스에서 서로 인접한 두 발신자 키(B의 마지막 행·A의 첫 행)로 시험한다. **증거는 `INNODB_LOCK_WAITS`의 빈 결과가 아니라 완료다** — 대기 행이 없다는 관측은 B가 아직 INSERT에 도달하지 않았거나 A의 잠금이 이미 풀린 뒤여도 같기 때문이다. 절차: ① A가 **실제 PK DELETE를 끝낸 뒤 커밋 직전에서 멈춘다**(시험이 커밋을 보류) ② **A를 해제하기 전에** B의 발송이 INSERT와 커밋까지 **완료**됐음을 확인(제한 시간 내 완료 단언) ③ 그 뒤 A를 해제. `INNODB_LOCK_WAITS`는 보조 증거(PROCESS 권한 관측 연결은 `MenuConcurrencyIntegrationTest`의 방식). **대조 시험(관측 장치 검증, R4-3)**: 같은 장치로 일부러 충돌하는 변형을 둔다 — 단 **RC에서는 FK·중복 키 검사 예외를 제외하면 갭 잠금이 쓰이지 않으므로**(MariaDB 격리 수준 문서) 서비스(RC)의 PK DELETE를 범위 DELETE로 바꾸는 것만으로는 B가 차단되지 않는다. 대조군은 **A만 시험 전용 `REPEATABLE READ` 트랜잭션**으로 실행한다(A의 `@@session.tx_isolation = 'REPEATABLE-READ'`를 단언). 인덱스·범위(`sender_id = A의 키`, `sent_at` 범위)·B의 삽입 위치(B의 마지막 행 뒤 = A의 첫 행 앞 갭)를 고정해 **실제로 같은 갭**을 쓰게 하고, 이때 B가 차단되고 대기 행이 관측됨을 확인해 관측 장치가 정상임을 증명한다. 정상 경로(발송 서비스)는 RC 유지
- **삭제 우회 불가(R1-3)**: 한도는 `admin_message`가 아니라 `admin_message_send_log`로만 센다. 보내고 양쪽이 지워도 이력은 24시간 남는다
- 값은 설정 `cms.message.rate.per-minute`(10)·`per-day`(300), 0 이하·누락 시 기동 실패
- 429 서비스 예외(`RateLimitedException`)와 핸들러 신설 — 필터와 같은 JSON(`code=RATE_LIMITED`, `Retry-After` 초), 메시지 "쪽지 발송 한도를 초과했습니다. 잠시 후 다시 시도해주세요."(F15). `Retry-After`는 가장 오래된 해당 창 내 이력이 창을 벗어나는 시각에서 계산
- 이력 정리가 발송 트랜잭션 안이라 마지막 발송 이후 이력은 남는다(§4). 발송이 실패(롤백)하면 이력도 없다

### 5-G. 감사 (D12)

- 보내기 서비스 메서드에 `@AdminActionLogged(actionType = MESSAGE_SEND, targetType = "MEMBER", targetIdExpression = "recipientId", safeErrorMessage = "쪽지 발송에 실패했습니다.")`. **서비스 메서드는 최상위 트랜잭션 진입점**(컨트롤러 → 서비스 직접 호출, 같은 클래스 내부 호출 금지 — `MemberPermissionService`와 같은 조건)
- **고정 오류 문구는 감사 저장 지점에서 결정한다(R3-1)**: `AdminActionLogAspect`는 기본적으로 `e.getMessage()`를 잘라 저장하는데, 서비스 내부 예외를 고정 문구로 만들어도 **서비스 메서드 반환 이후 트랜잭션 어드바이저가 던지는 flush·커밋 예외**(DB 예외 메시지에 SQL·값이 섞일 수 있음)는 막지 못한다. 그래서 `@AdminActionLogged`에 **선택 속성 `safeErrorMessage`(기본 빈 문자열)**를 추가하고, **값이 있으면** Aspect가 FAIL 감사의 `errorMessage`로 `e.getMessage()` 대신 그 고정 문구를 저장한다(`MESSAGE_SEND`만 사용). **속성이 비어 있는 기존 호출부의 동작은 불변**(`e.getMessage()` 저장), SUCCESS의 `errorMessage`는 null 그대로. 원래 예외와 원인 체인은 그대로 전파되어 `GlobalApiExceptionHandler`의 409·400 판정에 쓰인다. 이는 **공용 감사 모듈(`com.cms.admin.log`)의 소규모 확장**이므로 해당 `CLAUDE.md`에 기록하고 Aspect 단위 시험(속성 있음/없음)을 둔다
- **보장 범위: 감사 저장값 ≠ 서버 로그, 그리고 로그 보장은 "검증된 JDBC·세션 설정에서의 보장"이다(R4-1, R5-1)**: `safeErrorMessage`는 **감사 행에 저장되는 값만** 보호한다. Hibernate의 `SqlExceptionHelper`가 변환 중 `SQLException.getMessage()`를 ERROR로 출력하는 서버 로그는 감사 Aspect보다 먼저 실행되며 코드로 막을 수 없다(해당 로거를 끄면 전 기능의 진단이 사라진다). 서버 로그에 대한 보장은 **아래 전제가 모두 유지될 때에 한해** "DB 오류 메시지에 사용자 텍스트가 들어갈 경로가 없다"로 한정한다:
  - **스키마·검증(코드가 보장)**: ① 제목·본문 길이는 서비스가 선검증해 "Data too long"도 나오지 않는다 ② 유일 키는 자동 증가 PK와 상태 행(`ON DUPLICATE`로 오류 없음)뿐이다 ③ FK·CHECK 위반 메시지는 제약·테이블 이름만 담는다
  - **JDBC·세션 설정(운영 전제 — 코드로 강제하지 않는 값)**: ④ Connector/J(현재 빌드 3.5.8)의 진단 옵션 **`dumpQueriesOnException`·`includeInnodbStatusInDeadlockExceptions`가 꺼져 있어야 한다**(켜면 SQL·InnoDB 상태가 예외 메시지에 붙는다 — 운영 `DB_URL`은 외부 값) ⑤ 바인딩 값 로깅(`org.hibernate.orm.jdbc.bind`/`BasicBinder` TRACE)과 JDBC 쿼리 DEBUG 로깅이 꺼져 있어야 한다(설정에 없음 — `application-dev.yml`은 `show-sql: true`이나 값은 출력되지 않는다) ⑥ **연결 문자셋 `utf8mb4`와 strict SQL mode**여야 한다(테이블의 `utf8mb4` 선언만으로 연결 설정이 보장되지 않는다 — `Incorrect string value` 계열은 입력 일부를 포함할 수 있고 non-strict에서는 오류 대신 값 조정·경고가 난다). 정상 UTF-16·`utf8mb4` 연결에서는 이모지(4바이트) 자체가 오류 원인이 아니다
  - ④~⑥은 `docs/deployment.md`의 점검 항목으로 기록하고(운영 `DB_URL`·MariaDB 설정 확인), **시험은 테스트 연결의 `@@character_set_client`·`@@sql_mode`(strict)를 단언**하고 **4바이트 문자(이모지) 제목·본문의 왕복 저장·조회**를 검증한다. FK·CHECK·`Data too long`의 기본 메시지가 값을 담지 않는다는 사실만으로 전체 예외 출력의 안전성을 결론 내리지 않는다
  - 감사 Aspect의 저장 실패 경로 `loggingError`(감사 행 저장 자체가 실패할 때 그 예외를 로그에 남김)도 **정책 대상**이며 시험으로 확인한다. 본문 표식을 심은 요청에서 FK·CHECK·롤백 실패를 주입해 **로그 출력 전체(메시지·Throwable·cause)**에 표식이 없음을 검사한다(§7)
- **범위(R1-10)**: 감사는 **서비스에 진입한 발송 시도**만 기록한다. DTO 크기 상한·JSON 파싱 실패로 인한 400은 서비스에 들어가지 않으므로 기록되지 않는다(문서화). 서비스 안의 정규화·수신자·한도 실패는 FAIL 1건
- **호출 순서(R2 보강)**: **컨트롤러 인가(`@PreAuthorize`) → 가드(§5-J, 조회 종료) → 시도 버킷 소비(§5-E) → 감사 Aspect → 서비스 트랜잭션(`REQUIRES_NEW`, §5-F)**. 따라서 **가드 거부(403)와 트랜잭션 전 시도 제한 429는 발송 서비스 감사에 남지 않는다**(서비스에 진입하지 않음 — 문서화). 감사 Aspect는 서비스 커밋 이후 기록한다(기존 Aspect 순서가 커밋 이후 감사를 지원한다)
- **성공**: 반환 타입은 실제 `Long getRecipientId()`를 가진 클래스(record의 `recipientId()`나 중첩 getter는 Aspect 추출 규칙에 맞지 않는다 — F14). `targetLabel`은 없다
- **실패**: Aspect는 `targetId=null`(실패 시 수신자 ID를 추출하지 않는다 — F14)을 저장하고, **`errorMessage`는 `safeErrorMessage`(고정 한국어 문구)**를 저장한다(위 선택 속성 정책 — `e.getMessage()`를 저장하지 않는다). 서비스가 던지는 업무 예외(400·429 등)의 메시지도 고정 한국어 문구여야 하며 사용자 입력(제목·본문·수신자 입력값)을 담지 않는다(§5-B) — 이는 **HTTP 응답·핸들러 로그 쪽**의 규칙이고 감사 저장값은 속성이 보장한다. 원래 예외와 원인 체인은 그대로 전파된다(409 판정). 서버 로그의 DB 예외 출력은 위 "검증된 JDBC·세션 설정에서의 보장" 범위
- 읽음·삭제·검색·목록은 남기지 않는다
- 활동 로그 라벨: `AdminActionTypes`에 상수 `MESSAGE_SEND`와 `ALL` 포함, 라벨 맵은 `AdminActionTypes`가 아니라 **`templates/admin/log/manage.html`의 `ACTION_TYPE_LABELS`**에 "쪽지 발송" 추가(R1 정정)
- 애플리케이션 로그에 제목·본문을 찍지 않는다

### 5-H. 인가 변경 (D3)

- `AdminFeature.MY_INFO.gatePatterns`에 `"/admin/member/messages"` 추가 — **정확 경로**. 페이지 컨트롤러는 이 경로의 GET 하나(`@AdminPage`는 사이드바 모델 마커일 뿐 인가가 아니다 — F7). 메서드 보안을 걸지 않는다(페이지 차단은 URL 게이트의 HTML 403)
- **메서드 경계(R1-6, R2-5)**: ALWAYS 게이트는 HTTP 메서드를 구분하지 않으므로 이 경로에 POST 등을 추가하면 MANAGER 접근이 열린다. `SecurityConfig`를 쪼개지 않고 **컨벤션 시험 신설**: `/admin/member/messages`에 매칭되는 **모든 핸들러 매핑**은 GET/HEAD(읽기 전용 페이지)뿐이어야 하며, 그 밖의 메서드(ANY 포함) 핸들러가 생기면 CI가 실패한다.
  - **검사 방식(R2-5)**: 기존 `AdminEndpointAuthorizationConventionTest`의 스캐너는 `@RequestMapping` 경로 배열의 **첫 경로만** 읽으므로 재사용하지 않는다(F19). 새 시험은 스프링 컨텍스트의 **`RequestMappingHandlerMapping`에 등록된 실제 매핑 전체**를 순회해 각 `RequestMappingInfo`의 **모든 경로 패턴(별칭 포함)**이 `/admin/member/messages`와 매칭되는지(`PathPattern.matches`)와 `methods`가 GET/HEAD로 한정되는지(비어 있으면 ANY → 거부)를 검사한다
  - **반례(규칙이 위반을 실제로 잡는지)**: ① `@PostMapping("/admin/member/messages")` ② 두 번째 경로 별칭 `@PostMapping({"/admin/member/settings", "/admin/member/messages"})` ③ 메서드 제한 없는 `@RequestMapping("/admin/member/messages")` ④ 와일드카드 `/admin/member/*`에 걸리는 POST — 모두 실패해야 한다
- 시험: ADMIN·MANAGER(권한 0개) 200, USER 403, 비로그인 302, `/admin/member/messages/x`·`/admin/member/messagesX`는 MANAGER 403(캐치올), 같은 경로의 POST·PUT·DELETE는 **유효한 CSRF 토큰을 포함한 요청에서** 핸들러가 없어 405/404(게이트는 통과하지만 처리할 핸들러가 없음 — MANAGER 403이 아님을 기록), **CSRF 토큰이 없는 요청의 403은 별도 시험**으로 분리해 405 시험의 결과와 섞이지 않게 한다, 후행 슬래시 처리 대조
- `config/CLAUDE.md` 접근 제어 표에 승인 이력(2026-10-05) 기록

### 5-I. 화면

- **상단바**(`topbar.html` + `static/js/admin/topbar-message.js`): 봉투 아이콘 + 미읽음 배지(0이면 숨김, 99+), 페이지 로드 시 `unread-count` 1회(F5). 드롭다운: 최근 받은 쪽지 5건(보낸 사람·제목·시각·미읽음 표시) + [쪽지함 열기]. 항목 클릭 → 쪽지함 페이지 `?id=`로 이동해 상세를 연다(`^[1-9]\d{0,15}$`만, 연 뒤 `replaceState`로 제거). 검색·알림 드롭다운과 서로 닫힘
- **쪽지함 페이지**(`templates/admin/member/messages.html`): [받은 쪽지]·[보낸 쪽지] 탭(커서 [더 보기]), [쪽지 쓰기] 모달, 상세 모달(제목·본문 `textContent`+`pre-wrap`, 받은 쪽지면 단건 GET 뒤 읽음 PATCH — 큐 직렬화 후 count 재조회, [답장], [삭제])
- **배지 상태의 단일 소유자(R5-3)**: 미읽음 배지의 값·요청 세대·재조회는 **상단바 스크립트(`topbar-message.js`) 하나가 소유**한다. 쪽지함 페이지 JS는 `unread-count` API를 **직접 호출하지 않고** 상단바가 노출하는 좁은 인터페이스(예: `window.CmsMessageBadge`)를 쓴다 — ① **읽음·삭제를 시작할 때** `markChangeStarted()`로 진행 중인 count 요청의 응답을 무효화(세대 증가) ② 변경 요청이 끝나면 `refreshAfterChange()`가 count를 재조회하고 **그 이후에 시작한 요청의 응답만** 반영한다. 최초 로드 조회도 같은 세대 카운터를 쓴다. 이렇게 해야 `messages?id=42` 진입 시 상단바의 **지연된 최초 count 응답**이 상세 열람(PATCH·재조회) 이후에 도착해도 배지를 이전 값으로 되돌리지 못한다(폴링이 없어 복구 수단이 없다). 읽음 PATCH끼리의 큐 직렬화(F5)는 쪽지함이 유지한다. 상단바 스크립트가 없을 때(인터페이스 부재)는 쪽지함이 배지를 건드리지 않는다
- **수신자 자동완성(R1-8)**: 입력할 때마다 **선택된 `recipientId`를 즉시 해제**하고, 결과 항목을 클릭해야만 `recipientId`가 설정된다. 보내기 직전 화면은 "입력창에 보이는 이름 = 선택된 회원"을 다시 확인하고 불일치면 막는다. 검색 요청은 **요청 세대·`AbortController`**로 늦은 응답을 버리고 debounce는 UX용이며 서버 보호(D17)와 무관하다. **화면은 검색어를 보내기 전에 공백을 제거하지 않는다**(원문 보존 — R6-1: 서버가 원문 아이디 정확 일치를 먼저 조회하고 부분 검색에만 trim을 적용). `truncated`면 안내 표시. 429는 `Retry-After`를 안내
- **답장(R1-9)**: 수신자·`Re: ` + 원제를 미리 채우되 100 UTF-16 단위를 넘으면 **코드포인트 경계에서** 자른다(서로게이트 쌍을 자르지 않는다 — `slice`로 자르지 않는다). 시험: 제목 `Re: ` + `a`×95 + 이모지 경계
- 보낸 목록은 "읽음 10-05 14:03 / 안 읽음" 표시(D10)
- 비동기 결합: 상세 요청 세대·`AbortController`·응답 `id` 일치 검사(관리자 상세 모달 선례)
- 409(교착)는 "다시 시도해주세요" 안내, 401은 세션 만료 안내, 400·404·429는 서버 메시지 표시. 보내는 중 버튼 잠금
- XSS 시험 범위: 상세 본문뿐 아니라 **검색 결과 이름, 목록 제목, 답장 입력, 오류 표시, 상단바 드롭다운**까지

### 5-J. 현재 자격 확인 (D16, R1-4) — `MessageActorGuard`

- 모든 쪽지 API(보내기·목록·단건·읽음·미읽음 수·삭제·수신자 검색)는 컨트롤러가 먼저 `MessageActorGuard.requireActive(principalId)`를 호출한다. DB에서 본인 회원 행을 읽어 **상태가 `ACTIVE`이고 역할이 `ROLE_ADMIN`·`ROLE_MANAGER`일 때만** 통과, 그 밖은 `AccessDeniedException`(403)
- **가드는 `@Transactional(propagation = REQUIRES_NEW, readOnly = true, isolation = READ_COMMITTED)`의 스칼라 조회**(역할·상태 컬럼만 — **엔티티 로딩·캐시된 엔티티 기반 판정 금지**)로 실행한다(R3-2, R4 참고). 서비스의 `REQUIRES_NEW`는 **뒤따르는 서비스만** 격리하므로, 가드가 외부 RR 트랜잭션에 참여하면 외부 스냅샷의 낡은 상태("ACTIVE")를 읽어 통과시키고 이미 커밋된 비활성화를 놓친다(가드 통과 **이후** 경합과 다른, 가드 실행 **전에** 커밋된 변경). 가드 자체를 새 트랜잭션·RC로 분리해 이를 막는다. 정상 HTTP 흐름에서는 컨트롤러가 비트랜잭션이므로 가드는 자기 트랜잭션을 열고 서비스 호출 전에 닫는다. 가드 통과와 서비스 실행 사이의 짧은 틈(밀리초)에 상태가 바뀌는 경합은 수용한다(보장은 "가드 실행 시점의 DB 현재값")
- 비용: 요청당 PK 단건 조회 1회 추가. 배지(`unread-count`)는 관리자 페이지 로드마다 호출되므로 페이지당 1회 추가된다 — 수용(D16). 시험으로 쿼리 수를 기록
- 효과: 다른 인스턴스에 남은 낡은 세션, 만료 실패, 이미 필터를 통과한 요청 모두 현재 DB 상태를 따른다. **한계**: 이는 쪽지 API에 한정한다(공지 등 다른 기능은 기존 세션 계약 그대로 — 일관성이 다름을 문서에 명시)
- 수신자 자격(D5)은 별개 — 수신자는 `ACTIVE`가 아니어도 받을 수 있다(`LOCKED`·`PASSWORD_EXPIRED`)

## 6. 위험·불변식

| # | 위험 | 대응 |
|---|---|---|
| R1 | 남의 쪽지 열람·읽음·삭제(IDOR) | 모든 쿼리에 괄호까지 고정된 소유 조건, 경로에 회원 ID 없음, 없거나 남의 것 404. 교차 회원 통합 시험 |
| R2 | 저장형 XSS(제목·본문·이름) | 원문 저장, 화면은 `textContent`만, 자동 링크화 없음. 페이로드로 모든 표시 지점 시험(§5-I) |
| R3 | 수신자 상태 탐지(계정 열거) | 거부 사유 통합 문구, 검색 결과에 상태·역할 없음, 시도·검색 횟수 제한(D17). 수신 가능 집합 자체의 간접 추론은 D4 수용 |
| R4 | 명단 대량 수집(MANAGER) | **부분 검색 최소 2코드포인트, 1코드포인트는 아이디 정확 일치(원문)**·최대 10건·`truncated`·버스트 30 + 평균 분당 30(D17 — 첫 60초 최대 59회, 이후 평균 분당 30). **D17은 열거를 차단하지 않고 속도만 제한**한다(1문자 정확 조회로 영문·숫자 36개 후보를 약 12초에 순회 가능 — D4 수용 범위, R6 참고). 공개 필터와 분리. 다중 인스턴스 정확 제한은 하지 않음(단일 인스턴스·fail-open) |
| R5 | 스팸·대량 발송 | D11 + 삭제와 무관한 이력(D15) + 1:1(D6) + 길이 상한 |
| R6 | 빈도 제한 경합·우회 | 상태 행 잠금·선행 스냅샷 금지·잠금 후 `now`·이력 테이블(§5-F) |
| R7 | 발송 교착 | 회원 행이 아니라 발신자별 상태 행 잠금 + `READ_COMMITTED`. **순수 상호 발송은 교착 없음(409 0건 시험)**, **회원 수정(최후 ADMIN 가드의 활성 ADMIN 전체 잠금)과는 교착 가능 → 409 수용**(쪽지 미저장·재시도 안내), 오류 1020·1213 409 매핑 |
| R8 | 한쪽 삭제가 상대 보관함을 지움 | 쪽별 삭제 시각, 양쪽 삭제 때만 네이티브 조건부 DELETE, FK RESTRICT, 동시 삭제 시험(두 `innodb_snapshot_isolation` 값) |
| R9 | 고립 서로게이트·제어문자·양방향 제어 | 파이프라인(§5-B), 400, 고정 문구 |
| R10 | 인가 확대가 의도보다 넓어짐 | 정확 경로 1개 + GET/HEAD 전용 컨벤션 시험 + 하위·유사 경로 403 시험 |
| R11 | 낡은 세션의 쪽지 사용 | 모든 쪽지 API의 현재 상태 확인(D16) — 쪽지 한정 |
| R12 | 배지 폴링으로 세션 연장 | 페이지 로드 시 1회만 |
| R13 | 감사·로그에 대화 내용 유출 | **감사 저장값**은 `safeErrorMessage` 고정 문구로 보호(커밋 단계 예외 포함). **서버 로그**는 코드로 막을 수 없으므로(프레임워크 `SqlExceptionHelper`) 보장을 **"검증된 JDBC·세션 설정에서의 보장"**으로 한정한다 — 코드가 보장하는 전제(길이 선검증·유일 키는 PK뿐·FK/CHECK 메시지는 이름만)와 **운영 전제**(Connector/J 진단 옵션 `dumpQueriesOnException`·`includeInnodbStatusInDeadlockExceptions` 비활성, 바인딩·JDBC 쿼리 DEBUG 로깅 비활성, 연결 `utf8mb4`+strict mode)를 `docs/deployment.md`에 점검 항목으로 기록. 시험: 로그 캡처·`@@character_set_client`/`@@sql_mode` 단언·4바이트 왕복(R4-1, R5-1) |
| R16 | 배지가 쪽지함과 상단바 사이에서 되돌려짐 | 배지 값·요청 세대의 소유자를 상단바 스크립트 하나로 통합, 쪽지함은 인터페이스로만 갱신(변경 시작 시 기존 응답 무효화, 완료 후 재조회 값만 반영)(R5-3) |
| R17 | 한 글자·앞뒤 공백 아이디 계정이 검색으로 선택 불가 | 검색 원문 보존, **원문 그대로의 아이디 정확 일치를 먼저 조회**하고 부분 검색만 trim 후 2코드포인트부터. 보장은 "저장된 아이디를 원문 그대로 입력하면 선택 가능"(R5-2, R6-1) |
| R14 | 회원 하드 삭제 SQL | RESTRICT라 쪽지·이력·상태 행이 있으면 실패(조용한 연쇄 삭제 없음) |
| R15 | 발송 이력 누적 | 발송 시 본인 24시간 지난 이력 정리. 이후 발송하지 않는 회원의 이력은 남음(한계) |

## 7. 시험

1. 마이그레이션: V21 적용, FK(RESTRICT)·CHECK(자기 자신 INSERT 실패)·인덱스, **실패 복구 3분기**(정상 성공 이력·빈 잔여 객체 복구·데이터가 든 이력 불일치는 DROP하지 않음), V21 파일이 없는 마이그레이션 위치로 이전 앱 기동(`*:future` 무시 설정 확인)
2. 입력 정책 단위: 파이프라인 순서(raw 상한 → 서로게이트 → 정규화 → 문자 → 길이), 본문 `a`×1999 + CRLF 허용(정규화 후 2000 이내)·`a`×2001 거부, 제목 개행·U+2028·U+2029·양방향 제어 거부, ZWJ 이모지 허용, 제목 100/101, 본문 2000/2001, 고립 서로게이트, 오류 메시지에 입력 값 없음
3. 서비스 단위: 수신자 거부 조건 전부 같은 문구·자기 자신, 목록 커서·삭제 제외·size 상한·`box` 검증, 읽음 멱등·발신자 PATCH 404, 쪽별 삭제·이미 지운 쪽 404·양쪽 삭제 물리 삭제, 이력 기반 한도 경계(9/10번째, 24시간 창, **삭제해도 한도 회복 없음**), 수신자 검색(**원문 보존**: 원문 그대로의 아이디 정확 일치를 먼저 조회, **1코드포인트 입력은 그 정확 일치만**·부분 일치는 trim 후 2코드포인트부터·자기 제외·상태 필터·LIKE 이스케이프·정확 일치 우선·`truncated`·필드 최소 — **한 글자 아이디·이름 계정과 앞뒤 공백이 든 아이디(`userId=" a"`)가 저장된 아이디를 원문 그대로 입력하면 선택 가능**, R5-2·R6-1), 가드(**`ACTIVE`가 아니거나 `ROLE_USER`로 전환된 계정 403**, ADMIN→MANAGER 강등은 §5-J의 허용 집합 안이라 통과)
4. 컨트롤러 슬라이스: 인가(ADMIN·MANAGER 200/201, USER 403, 비로그인 JSON 401), CSRF 없는 POST·PATCH·DELETE 403, 400·404·409·429 형식(`RATE_LIMITED`, `Retry-After`)
5. 인가 통합·컨벤션: 쪽지함 페이지 ADMIN·권한 0개 MANAGER 200, USER 403, 하위·유사 경로 MANAGER 403, `AdminFeatureTest`·`SecurityConfigTest` 갱신, **`RequestMappingHandlerMapping` 등록 매핑 전체를 보는 GET/HEAD 전용 컨벤션 시험 + 반례 4종(정확 경로 POST·두 번째 경로 별칭·메서드 제한 없음·와일드카드)**, 405 시험(유효 CSRF)과 CSRF 누락 403 시험 분리
6. 통합(Testcontainers):
   - 교차 회원 IDOR(조회·읽음·삭제 404, 원본 불변), 단건 읽음 경합
   - **동시 양쪽 삭제**: `innodb_snapshot_isolation`이 ON·OFF인 **전용 컨텍스트 두 경우 모두**에서 양쪽 UPDATE 성공·DELETE 합계 1·최종 행 부재. `@@version`·`@@session.tx_isolation`(10.11 변수명)을 기록하고 전역 설정은 변경하지 않는다. **사전 조건 단언(R4-6)**: 고정 이미지에서 `innodb_snapshot_isolation` 변수가 존재함 — 없으면 건너뛰지 않고 실패(이미지 변경은 사용자 결정, §5-D)
   - **트랜잭션 계약**: 서비스 트랜잭션 안에서 `@@session.tx_isolation = 'READ-COMMITTED'`를 단언하고, **호출자가 외부 트랜잭션에서 먼저 일반 SELECT로 스냅샷을 연 채로** 서비스를 호출해도(REQUIRES_NEW라 새 연결·새 트랜잭션) 직전 커밋된 발송이 한도에 반영되고 오류 1020이 나지 않음(R2-4). 정상 HTTP 흐름의 **최대 동시 점유 연결은 1**(HikariCP MXBean active 최대치 — 요청 전체의 연결 개수가 아님)
   - **가드 외부 트랜잭션 반례(R3-2)**: 외부 RR 트랜잭션이 회원의 ACTIVE 상태를 읽어 둔 뒤 → 다른 연결에서 그 회원을 DISABLED로 변경·커밋 → 같은 외부 트랜잭션에서 가드·발송 호출 → **403**(가드가 새 트랜잭션·RC 스칼라 조회라 외부 스냅샷의 낡은 값을 읽지 않음)
   - **감사 FAIL 메시지·롤백(R3-1, R4-2)**: 발송 서비스에 **실제 커밋 전에** 실패하는 장치를 주입한다 — **`TransactionSynchronization.beforeCommit`에서 예외를 던진다**(`MenuConcurrencyIntegrationTest`의 선례). `MemberPermissionApiIntegrationTest`의 DataSource 프록시는 **실제 `commit()`을 수행한 뒤** 예외를 던지는 "커밋 응답 유실" 모사라 롤백 시험의 선례가 **아니다**. 검증: **쪽지·이력 롤백, SUCCESS 감사 없음, FAIL 감사 정확히 1건, `errorMessage`가 고정 문구이며 제목·본문·수신자 입력·SQL 원문을 포함하지 않음**. **커밋 응답 유실은 별도 시험**으로 분리하고 그 경우에는 "저장되지 않았다"고 단언하지 않는다(DB는 이미 커밋돼 있고 FAIL 감사만 남는 한계로 기록 — 호출자가 재시도하면 중복 쪽지가 생길 수 있음). `safeErrorMessage` 속성이 비어 있는 기존 호출부는 `e.getMessage()` 저장이 불변임을 Aspect 단위 시험으로 확인
   - **서버 로그 비유출(R4-1, R5-1)**: 본문 표식(고유 문자열)을 심은 요청에서 FK 위반·CHECK 위반·`beforeCommit` 롤백을 각각 주입하고 **로그 출력 전체(메시지·Throwable 스택·cause 체인)에 표식이 없음**을 로그 캡처로 검사한다. 감사 저장 자체가 실패하는 경로(`AdminActionLogAspect`의 `loggingError`)도 포함. **사전 조건 단언**: 바인딩·JDBC 쿼리 로깅이 꺼져 있고, 테스트 연결의 `@@character_set_client`가 `utf8mb4` 계열이며 `@@sql_mode`가 strict(`STRICT_TRANS_TABLES` 또는 `STRICT_ALL_TABLES`)이고 Connector/J 진단 옵션 2종이 꺼져 있다(테스트 JDBC URL·설정 확인). 이 보장은 **검증된 설정에서만** 성립함을 시험 이름·주석에 명시
   - **4바이트 문자 왕복(R5-1)**: 이모지(서로게이트 쌍) 제목·본문을 저장·조회해 손상·치환 없이 되돌아오고 오류·경고가 없음
   - **배지 경합(R5-3)**: `messages?id=` 진입에서 상단바의 최초 `unread-count` 응답을 의도적으로 늦춘 채 상세 열람(읽음 PATCH·재조회)을 끝낸 뒤 늦은 최초 응답이 도착해도 **배지가 서버 값(0)으로 유지**됨(playwright 또는 JS 단위 시험). 쪽지함 JS가 `unread-count` API를 직접 호출하지 않음도 확인
   - **수신자 거부 순서 조건표(R3-3·R4-4·R4-5)**: §5-F의 조건표대로 — 검증 전 `DISABLED` 커밋 → 고정 문구 400·미저장·FAIL 감사 / 검증 전 ADMIN→MANAGER 강등 → 통과(201) / 검증 후 변경 → 201 / 실제 교착 희생자 → 409. 중단 지점은 **수신자 스칼라 조회 반환 직후·쪽지 INSERT 이전**, **발신자는 MANAGER**·수정 실행자는 별도 ADMIN. `ROLE_USER` 수신자는 SQL fixture 방어 시험(API로 만들 수 없음)
   - **동시 발송 한도**: 같은 발신자의 병렬 발송에서 상한이 정확, 두 `innodb_snapshot_isolation` 값 모두에서(R2-3), COUNT 이후 수신자 수정이 끼어드는 경합 포함
   - **순수 상호 발송 A→B/B→A 동시**: **둘 다 201, 409 0건**(R2-1 — 임의의 409를 성공으로 인정하지 않는다)
   - **회원 수정과 섞인 경합**: 위 조건표의 각 행을 시험하되 **공통 불변식(500 없음·부분 저장 없음·한도 정확)**을 모든 행에서 단언하고, 쪽지 미저장일 때의 응답은 **조건표가 정한 것**(검증 전 `DISABLED` → 400, 교착 희생자 → 409)을 따른다 — 무조건 409를 기대하지 않는다. 실제 ADMIN↔ADMIN 교착 시험은 순서 시험과 **분리**
   - **인접 발신자 이력 정리(R2-2, R3-5, R4-3)**: A가 PK DELETE 후 커밋 직전에 멈춘 채 B의 인접 키 발송이 **INSERT·커밋까지 완료**(증거 = 완료, `INNODB_LOCK_WAITS`는 보조). **대조 시험**: **A만 시험 전용 `REPEATABLE READ` 트랜잭션**(격리 수준 단언)으로 같은 갭을 범위 DELETE로 잠그면 B가 차단되고 대기 행이 관측됨을 확인(RC 서비스의 범위 DELETE 변형은 갭 잠금이 없어 대조군이 되지 못한다)
   - **`innodb_snapshot_isolation` 설정 격리(R3-4)**: ON·OFF 전용 Spring 컨텍스트(`connection-init-sql`, `@DirtiesContext(AFTER_CLASS)`)에서 실행하고 서비스 트랜잭션 안에서 `@@session.innodb_snapshot_isolation` 값을 단언 — 다른 시험 클래스의 풀에 설정이 새지 않음
   - **409 매핑**: 원인 체인의 MariaDB 오류 1020·1213을 가진 예외 → 409(단위 시험)
   - **쪽지 제한(R2-6)**: 같은 IP의 서로 다른 회원 독립·같은 회원의 여러 세션은 같은 버킷·`cms.rate-limit.enabled=false`에서도 D17 유지·버스트 30 + 평균 분당 30(첫 60초 최대 59건 허용 계약 단언)
   - 발송→양쪽 삭제 자동화 루프가 한도를 우회하지 못함
   - 감사 행(수신자 ID·본문 없음·실패 FAIL의 `errorMessage`에 입력 없음)·서버 로그에 본문 없음
   - 수신자 `DELETED` 전이 직후 발송(한계 확인), 회원 하드 삭제 시 RESTRICT 실패
   - 낡은 세션(DB에서 DISABLED/ROLE_USER로 바뀐 회원의 기존 세션)이 모든 쪽지 API에서 403
7. `ClockUsageConventionTest`·`AdminEndpointAuthorizationConventionTest`·`AdminPageAnnotationConventionTest` 통과
8. **인덱스·검사 행 수(R1-11)**: 시험 데이터에 "최신 10만 건이 수신측 삭제됐지만 발신측에 남은 회원"을 포함해 받은 목록 첫 페이지·`beforeId` 페이지·미읽음 수·보낸 목록의 **검사 행 수·실행 시간**을 기록하고 통과 기준(예: 첫 페이지 검사 행 ≤ 수십 건)을 구현 단계 1에서 확정한다. 인덱스 사용 여부만으로 통과시키지 않는다. 이력 집계·수신자 검색도 같은 방식
9. playwright: ADMIN이 MANAGER 검색(정확 일치 우선·`truncated`)·발송 → MANAGER 배지·드롭다운·`?id=` 상세(XSS 페이로드가 문자 그대로)·읽음(배지 감소) → ADMIN 보낸 쪽지함 읽음 표시 → 답장(이모지 경계) → 한쪽 삭제 후 상대에겐 남음 → 한도 초과 429 안내 → 입력 변경 시 선택 해제. 시험 데이터는 끝나면 삭제(DB: `docker exec cms-db-dev mariadb -uadmin -p1234 cms`)

## 8. 단계 (각 단계 끝에 `./gradlew test`)

1. V21(세 테이블) + 엔티티·리포지토리 + 마이그레이션 시험 + `TestMembers.delete` 확장 + 429 서비스 예외·핸들러 + **오류 1020·1213의 409 매핑 보강** + **`@AdminActionLogged.safeErrorMessage` 속성과 Aspect 반영·단위 시험(R3-1) + `com.cms.admin.log` `CLAUDE.md` 갱신** + 인덱스·검사 행 수 통과 기준 확정 + `MessageRateLimiter`(공개 필터와 분리, `Bucket` 재사용 여부 대조)
2. 입력 정책 + 가드 + 보내기(상태 행 잠금·이력·수신자·감사) + 수신자 검색 + API 시험 + **`AdminActionTypes.MESSAGE_SEND` 상수·`ALL`·활동 로그 화면 라벨(`ACTION_TYPE_LABELS`)을 이 단계에 함께 추가**(`AdminActionTypeLabelSyncTest`·`AdminActionTypeSyncTest`가 중간 단계에서 깨지지 않게)
3. 목록·단건·읽음·삭제(네이티브 DELETE) + 동시성 시험(두 설정값)
4. 인가 변경(`MY_INFO` 게이트) + 쪽지함 페이지 + GET/HEAD 컨벤션 시험
5. 상단바 봉투·드롭다운 + 쪽지함 화면 JS
6. 문서(패키지 `CLAUDE.md`, 루트 지도, `config`·`permission` `CLAUDE.md`, `com.cms.admin.log` `CLAUDE.md`, migration-guide, **`docs/deployment.md`의 JDBC·세션 설정 운영 전제 점검 항목(R5-1)**) · playwright (활동 로그 라벨은 2단계로 이동)

## 9. 문서

- `com.cms.admin.message` `CLAUDE.md` 신설(입력 파이프라인·삭제 알고리즘·상태 행/이력 구조·가드·한도 한계·IDOR·검색 노출 범위)
- 루트 `CLAUDE.md` "지침 파일 지도"에 추가
- `config/CLAUDE.md`: `MY_INFO` 행에 `/admin/member/messages` 추가 + 승인 이력(2026-10-05) + GET/HEAD 컨벤션 언급
- `permission/CLAUDE.md`: 카탈로그 표의 `MY_INFO` 설명, PR B 번호 "V21 이상" → "V22 이상"(D13)
- `docs/migration-guide.md` "V21 실패 복구"(데이터 확인 3분기)
- `com.cms.admin.log` `CLAUDE.md`: `@AdminActionLogged.safeErrorMessage`(고정 오류 문구 — 값이 있으면 FAIL 감사 `errorMessage`로 `e.getMessage()` 대신 저장, 비어 있으면 기존 동작)
- `docs/deployment.md`: 쪽지 비유출 보장의 JDBC·세션 설정 전제(Connector/J `dumpQueriesOnException`·`includeInnodbStatusInDeadlockExceptions` 비활성, JDBC 쿼리·바인딩 DEBUG 로깅 비활성, 연결 문자셋 `utf8mb4`·strict mode) 점검 항목(R5-1)
- 활동 로그 화면 라벨 `ACTION_TYPE_LABELS`

## 10. 미결 사항

- 없음(Q1~Q12 → D3~D14, 리뷰 1라운드 결정 → D15~D17). 구현 단계에서 확정할 값: 인덱스 3개 vs 대안(실측), 검사 행 수 통과 기준, 토큰 버킷의 회원 ID 키 지원 방식

## 구현 메모 (2026-10-05)

- 구현 완료(브랜치 `feat/admin-message`, 커밋·PR 전). 전체 `./gradlew test` 통과, playwright로 실제 앱(ADMIN↔MANAGER 발송·수신자 검색·XSS 페이로드 표시·`?id=` 상세·읽음·답장·삭제·읽음 확인·배지 경합) 확인.
- **시험 이미지 결정은 필요 없었다**: 고정 digest의 MariaDB는 10.11.19이고 `innodb_snapshot_isolation`이 존재한다(기본 OFF) — §10의 "미지원이면 사용자 결정" 분기에 해당하지 않았다.
- 계획과 달라진 점:
  1. `cms.message.*` 설정은 **기본값이 있어 생략해도 기동**하고, 0 이하·상한 초과만 기동 실패한다(계획 §5-F의 "누락 시 기동 실패"보다 완화 — 기본값이 의미 있는 값이라서).
  2. `readAt`은 미읽음이면 **JSON 키가 생략**된다(프로젝트 전역 Jackson NON_NULL). 화면은 키 부재를 미읽음으로 처리한다.
  3. 동시성 시험의 분당 한도를 4로, 스레드를 8로 낮췄다 — Hikari 기본 풀(10)이 경합을 한도와 같은 수로 제한해 잠금 제거 변이가 통과하는 것을 변이 실험으로 발견했다(`docs/troubleshooting.md`).
  4. 시험 데이터의 시각은 DB `NOW()`(컨테이너 UTC)가 아니라 앱 `Clock`(KST)으로 넣는다(`docs/troubleshooting.md`).
  5. 가드 외부 스냅샷 반례·서버 로그 비유출(FK 위반·`beforeCommit` 롤백)·인덱스 핸들러 읽기 수·ON/OFF 전용 컨텍스트·GET/HEAD 컨벤션 시험을 모두 구현했고, **변이 실험으로 감도를 확인했다**: 상태 행 잠금 제거·발송 격리를 RR로·`REQUIRES_NEW`→`REQUIRED`·물리 DELETE 제거·가드 `REQUIRES_NEW`→`REQUIRED`·쪽지함 컨트롤러에 POST 추가가 각각 해당 시험을 실패시킨다.
- 측정한 값: 최신 10만 건이 한쪽 삭제된 회원의 목록·미읽음·보낸 목록·이력 집계가 InnoDB 핸들러 읽기 **2~68회**(기준 300 미만), 삭제 열 없는 단순 인덱스 대조군은 5만 회 초과.
- **확인하지 못한 것**: (1) 운영 `DB_URL`·MariaDB 설정의 JDBC 진단 옵션·문자셋·strict 모드(`docs/deployment.md`에 점검 항목으로만 기록 — 코드가 강제하지 않는다) (2) 수신자 부분 검색(`%keyword%`)의 대규모 데이터 비용(관리자·매니저 행 수가 소수라는 전제) (3) 429·409 안내 문구의 화면 표시, 모바일 폭 레이아웃, 작성 모달에서 선택 후 입력을 바꿀 때의 선택 해제를 브라우저로 직접 확인하지 못했다(코드 경로와 서버 응답은 시험으로 고정) (4) JS 단위 시험은 없다(playwright 수동 확인만).
