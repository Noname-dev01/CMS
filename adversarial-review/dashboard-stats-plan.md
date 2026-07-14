# 대시보드 실데이터 구현 계획 (리뷰 반영본 — 구현 완료)

## Context

관리자 대시보드(`/admin`, `templates/admin/index.html`) 상단 통계 카드 4종(신규회원 / 오늘 방문자 / 이번달 방문자 / 총 방문자)이 SB Admin 2 테마 더미값(`$40,000`, `$215,000`, `50%`, `18`)으로 하드코딩돼 있었다. 이를 실제 DB 집계로 표시하도록 구현 완료.

**6차 adversarial 리뷰 이후 추가 발견한 인가 설계 결함**:
- MANAGER가 자기 자신 "내 정보"(조회·수정·비밀번호·프로필 이미지)에 접근해야 함 → SecurityConfig URL matcher + `@PreAuthorize` 두 계층을 모두 수정.
- "오늘 방문자" 카드의 달러 아이콘(`fa-dollar-sign`) → `fa-eye`로 교체.

---

## 핵심 확정 사항 (불변식)

- **방문자 = `ROLE_ADMIN`·`ROLE_MANAGER` 로그인 성공 1건 = 방문 1건** (성공 핸들러에서 권한 확인 후 기록)
- **신규회원 = 이번 달 가입한 관리 계정 수** (`ROLE_ADMIN`·`ROLE_MANAGER`, `DELETED` 제외)
- 이번달 방문자 카드의 진행률 바(50%)는 **제거하고 숫자만** 표시
- 통계 실패 시 4개 필드 모두 `null` → 템플릿에서 `-` 표시 (전체 단위 폴백, 0건 정상과 장애 구분)
- 통계 기준 시간대 **Asia/Seoul**: `CmsApplication.main()`에서 `TimeZone.setDefault` 강제(1차 보장) + `Clock` 주입(테스트 가능성)
- **MANAGER 허용 범위**: 대시보드(`/admin`) + 자기 자신 내 정보(`/admin/member/info` + self API)

---

## 구현된 파일 목록

### 신규 생성

| 파일 | 역할 |
|------|------|
| `admin/visit/domain/VisitLog.java` | 방문 기록 엔티티 (`visit_log` 테이블, `ddl-auto:update` 자동 생성) |
| `admin/visit/repository/VisitLogRepository.java` | 반열린구간 count 쿼리 |
| `config/auth/VisitLoggingAuthenticationSuccessHandler.java` | 로그인 성공 시 방문 기록 + 리다이렉트 위임. `@PostConstruct`로 `/admin` target 설정. try-catch 예외 격리, IP 45자 절단 |
| `admin/dashboard/dto/response/DashboardStatsResponse.java` | 통계 DTO (nullable Long 4종. null = 장애, 0L = 정상 0건) |
| `admin/dashboard/service/DashboardService.java` | 통계 집계 서비스. 비트랜잭션 + 전체 try-catch 폴백. Clock 주입 |
| `config/AppConfig.java` | `Clock.system(Asia/Seoul)` 빈 등록 |
| 테스트: `DashboardServiceTest`, `AdminMainControllerTest`, `VisitLoggingAuthenticationSuccessHandlerTest`, `VisitLogRepositoryDataJpaTest` | 슬라이스/단위/DataJpa 테스트 |

### 수정

| 파일 | 변경 내용 |
|------|-----------|
| `CmsApplication.java` | `SpringApplication.run()` 전에 `TimeZone.setDefault(Asia/Seoul)` |
| `config/SecurityConfig.java` | `.successHandler(handler)` 교체 + MANAGER matcher 추가(대시보드·내 정보 self API) |
| `admin/member/controller/AdminMemberController.java` | me 6개 메서드 `@PreAuthorize` → `hasAnyRole('ADMIN','MANAGER')` |
| `admin/member/repository/MemberRepository.java` | `countByUserTypeInAndStatusNotAndCreateDate...` 파생 쿼리 추가 |
| `admin/AdminMainController.java` | `DashboardService` 주입 + `model.addAttribute("stats", ...)` |
| `templates/admin/index.html` | 카드 4종 `th:text` + `?: '-'`, 카드3 진행률 바 제거, 카드2 아이콘 `fa-eye` |
| `templates/admin/fragments/sidebar.html` | `xmlns:sec` 추가 + 관리자조회/추가·시스템 섹션 `sec:authorize="hasRole('ROLE_ADMIN')"` (내 정보는 모두 표시) |
| `config/SecurityConfigTest.java` | 핸들러 mock 빈 추가 + MANAGER 인가 범위 검증 테스트 추가 |
| `config/ApiSecurityConfigTest.java` | 핸들러 mock 빈 추가 |
| `admin/member/controller/AdminMemberControllerTest.java` | MANAGER me 접근 허용·비self 403 검증 추가 |

---

## SecurityConfig MANAGER 인가 정책 (구체적 경로 우선)

```
.requestMatchers("/admin/login", "/admin/login-error").permitAll()
.requestMatchers("/swagger-ui.html", ..., "/v3/api-docs/**").hasRole("ADMIN")
// MANAGER 허용
.requestMatchers("/admin").hasAnyRole("ADMIN", "MANAGER")
.requestMatchers("/admin/member/info").hasAnyRole("ADMIN", "MANAGER")
.requestMatchers("/admin/api/members/me", "/admin/api/members/me/**").hasAnyRole("ADMIN", "MANAGER")
// 그 외 전부 ADMIN 전용
.requestMatchers("/admin/**").hasRole("ADMIN")
.anyRequest().permitAll()
```

**주의**: `/admin/api/members/me`(정확 경로)와 `/admin/api/members/me/**`(하위) **둘 다** 명시 필수 — `/**`는 자기 자신 경로를 포함하지 않는다.

---

## DashboardService 그레이스풀 다운 설계

- 서비스 레벨 `@Transactional` 미사용 → 각 count가 Spring Data 기본 개별 readOnly 트랜잭션
- 비트랜잭션 메서드 전체를 단일 try-catch로 감쌈 → 커밋/정리 시점 예외까지 통제
- 어느 count라도 실패 시 4개 필드 모두 `null` 반환 + error 로그
- 템플릿에서 `null`은 `-`로 렌더링(장애가 정상 0건처럼 보이지 않음)

---

## 검증 (end-to-end)

1. `./gradlew test` — MariaDB 없는 환경에서는 `VisitLogRepositoryDataJpaTest`·`CmsApplicationTests` 제외하고 전체 통과.
2. `./gradlew bootRun`(`make dev-up`) 기동 → 기동 로그에서 **JVM 기본 시간대 Asia/Seoul** 확인.
3. playwright: `admin`/`1234` 로그인 → VisitLog 1건 적재 확인.
4. `/admin` 카드 4종: 총/오늘/이번달 방문자 ≥ 1, 신규회원 = 이번 달 가입 수, 카드3 진행률 바 없음, 카드2 눈 아이콘. `-` 보이면 폴백 발동 → 앱 로그 확인.
5. MANAGER 시나리오: `POST /admin/api/members`로 MANAGER 계정 생성 → 로그인 → 대시보드 정상 + 사이드바에 내 정보만 보임 + 관리자조회/추가·활동 로그 미표시 + `/admin/member/info` 정상 + `/admin/member/manage` 403.
6. ADMIN 재로그인 → 전체 메뉴·기존 화면 회귀 없음.
