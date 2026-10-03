# CLAUDE.md — src/test/java

이 디렉터리(테스트 코드) 작업 시에만 로드된다. 공통 규칙은 프로젝트 루트 `CLAUDE.md` 참조.

- 컨트롤러 테스트는 `MockMvc`를 사용한다.
- 시큐리티가 걸린 엔드포인트는 `spring-security-test`(`@WithMockUser` 등)를 활용한다.
- **위임 기능(공지 등)을 다루는 MANAGER 테스트는 `@WithMockUser(roles = "MANAGER")`를 쓰지 않는다** — 권한 판정 키가 회원 ID라 회원 ID가 없는 주체는 위임 기능이 전부 거부된다(거부 단언이 엉뚱한 이유로 통과해 약해지기도 한다). 슬라이스는 `@WithManager`(`com.cms.config`, 회원 ID `PermissionTestConfig.MANAGER_ID`를 가진 `CustomUserDetails`), 통합은 `TestMembers.save`로 실제 회원 행을 만들고 `TestMembers.asMember`로 주체를 만든다(`member_permission` FK 때문에 정리는 `TestMembers.delete`). 상시 허용·ADMIN 전용 경로 시험은 `@WithMockUser`를 그대로 써도 된다.
- 슬라이스 테스트(`@WebMvcTest`, `@DataJpaTest`)를 우선하고, 통합 테스트(`@SpringBootTest`)는 필요한 경우에만 사용한다.
- **DB에 실제로 접속하는 테스트는 Testcontainers를 사용한다** (2026-07-27 도입, `com.cms.support.MariaDbContainerSupport`). 대상 테스트 클래스가 이 베이스 클래스를 `extends`하면 실행 시점에 일회용 MariaDB 컨테이너가 자동 기동된다 — **로컬 DB 기동(`make dev-db`)이나 `DB_PASS`/`MAIL_USER`/`MAIL_PASS` 환경변수 주입 없이 `./gradlew test`만으로 전체 테스트가 통과**한다. 필요한 건 Docker뿐이다. `build.gradle`의 `maxParallelForks=1`+`forkEvery=0`으로 테스트 JVM(워커)당 컨테이너 1개를 보장하며, 재사용(`reuse.enable`)은 쓰지 않아 매 실행이 깨끗한 DB에서 시작한다(Flyway가 V1부터 전체 마이그레이션 적용). 상세 설계 결정은 `adversarial-review/plan/PLAN-testcontainers.md` 참조.
