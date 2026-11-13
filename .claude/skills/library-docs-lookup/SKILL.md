---
name: library-docs-lookup
description: 라이브러리 API가 불확실하거나 버전에 민감한 작업 전 context7로 공식 문서를 조회하는 방법, 화면(templates·static) 변경 후 playwright로 UI를 검증하는 절차와 스크린샷 저장 위치(docs/screenshots/<카테고리>/)를 안내한다. QueryDSL·Spring Data JPA·Spring Security·SpringDoc·Thymeleaf·Spring Boot API를 다루거나, 화면을 수정한 뒤 검증할 때, playwright로 스크린샷을 찍어 남길 때 사용한다.
---

# 라이브러리 문서 조회 · UI 검증 지침

## context7 — 라이브러리 공식 문서 조회

라이브러리 API가 **불확실하거나 버전에 민감한 작업**일 때는 코드를 작성하기 전에 context7로 최신 공식 문서를 확인한다 (훈련 데이터가 오래됐을 수 있다). 확신이 있는 안정된 API까지 매번 조회할 필요는 없다.

**확신과 무관하게 조회하는 조건** — 옛 버전 기억은 확신을 동반하므로 "불확실하다는 느낌"에만 맡기지 않는다. 아래에 걸리면 조회(또는 소스·실측)로 확인한다:
- `build.gradle`에 의존성·스타터를 추가하거나 버전을 바꿀 때
- `application*.yml`에 설정 키를 새로 쓰거나, 설정하지 않은 기본값에 기댈 때
- 이 프로젝트에서 처음 쓰는 어노테이션·클래스·설정일 때(기존 코드에 쓴 곳이 있으면 그 코드가 근거다)
- 학습 데이터 이후에 나왔을 수 있는 도구·버전일 때(예: playwright-mcp, 최신 패치 버전)
- 오류 메시지나 동작이 예상과 다를 때

상황별 조회 대상:

| 상황 | 조회 대상 |
|------|-----------|
| QueryDSL 동적 쿼리·`BooleanExpression` 조합 | `querydsl` |
| Spring Data JPA `Specification` / Pageable 정렬 | `spring-data-jpa` |
| Spring Security 필터 체인·메서드 보안 설정 | `spring-security` |
| SpringDoc OpenAPI 어노테이션·Swagger 커스터마이징 | `springdoc-openapi` |
| Thymeleaf 레이아웃·조각(fragment) 문법 | `thymeleaf` |
| Spring Boot 설정·자동 구성 변경사항 | `spring-boot` |

사용 순서: `resolve-library-id`로 ID와 Versions 확인 → 프로젝트 버전(`build.gradle`)과 같거나 같은 마이너의 가장 가까운 버전으로 `query-docs`(`/org/project/<버전>` 형식, 버전 없이 조회하지 않는다). 버전이 다르면 출처에 적는다(예: "context7 v4.0.3 — 프로젝트 4.0.8").
- 설정 키·기본값·자동 구성은 정확한 버전 jar의 메타데이터로 확인한다 — `~/.gradle/caches/modules-2/files-2.1/<group>/<artifact>/<버전>/`의 jar 안 `META-INF/spring-configuration-metadata.json`·`META-INF/spring/…AutoConfiguration.imports`(`unzip -p <jar> <경로>`로 읽기만 한다. 소스 jar는 캐시에 없다).
- 같은 마이너 버전도 없거나 문서에 답이 없으면 릴리스 노트·소스·실측으로 확인한다.

## playwright — 브라우저 UI 검증

화면(`templates/**`·`static/**`)을 수정하거나 새 페이지를 추가한 경우 playwright로 직접 확인한다.

**기본 접속 정보**
- URL: `http://localhost:8080`
- 로그인: 사전에 DB에 등록된 관리자 계정 (`dev` 프로파일에서 DB가 비어 있으면 `TestMemberLoader`가 `admin`/`1234` 계정을 자동 생성)
- 로그인 경로: `/admin/login`

**검증 우선순위**
1. 로그인 → 해당 화면 진입 → 핵심 기능 동작 (골든 패스)
2. 폼 유효성 검사 메시지 노출 여부
3. API 호출 후 화면 갱신(목록 reload, 성공/오류 토스트 등)
4. 다른 화면에 회귀 오류가 없는지 스크린샷으로 확인

앱이 실행 중이 아닐 때는 `./gradlew bootRun --args='--spring.profiles.active=dev'`(또는 `make dev-up`)을 먼저 실행한다.
playwright로 확인할 수 없는 환경이라면 그 사실을 명시하고 완료를 주장하지 않는다.

**스크린샷 저장 위치**
- 보고·계획 문서에 근거로 남길 스크린샷은 `browser_take_screenshot`에 `filename: "docs/screenshots/<카테고리>/<이름>.png"`를 준다. 상대 경로는 프로젝트 루트 기준으로 풀리고 상위 폴더는 자동으로 만들어진다(workspace 밖 경로는 거부됨).
- 카테고리는 기존 폴더(`auth`·`board-admin`·`error-pages`·`member`·`public-board`·`public-home`·`rate-limit`·`site-page` 등 — `docs/screenshots/`를 먼저 확인)를 우선 쓴다. 맞는 곳이 없으면 기능 영역을 나타내는 kebab-case 이름으로 새로 만들고(`admin`/`public` 구분을 기존 이름처럼 붙임 — 예: `faq-admin`, `public-faq`), 결과 보고에 "새 카테고리 생성"을 한 줄로 적는다.
- 파일 이름은 `<상황>-<순번>-<결과>.png`를 권장한다(예: `lockout-01-locked-correct-password-rejected.png`).
- `filename` 없이 찍은 확인용 스크린샷·콘솔 로그·스냅샷은 `.playwright-mcp/`에 자동 저장된다. 남길 필요가 없는 것은 거기에 둔다.
- `docs/screenshots/`와 `.playwright-mcp/`는 모두 `.gitignore` 대상이라 커밋되지 않는다. 문서에는 경로만 적는다.
