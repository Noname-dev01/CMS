---
name: library-docs-lookup
description: 라이브러리 API가 불확실하거나 버전에 민감한 작업 전 context7로 공식 문서를 조회하는 방법, 복잡한 다계층 작업 전 sequential-thinking으로 사전 설계하는 기준, Thymeleaf 화면 변경 후 playwright로 UI를 검증하는 절차를 안내한다. QueryDSL·Spring Data JPA·Spring Security·SpringDoc·Thymeleaf·Spring Boot API를 다루거나, 여러 레이어에 걸친 신규 기능·리팩터링·마이그레이션을 계획하거나, Thymeleaf 페이지를 수정한 뒤 검증할 때 사용한다.
---

# 라이브러리 문서 조회 · 사전 설계 · UI 검증 지침

## context7 — 라이브러리 공식 문서 조회

라이브러리 API가 **불확실하거나 버전에 민감한 작업**일 때는 코드를 작성하기 전에 context7로 최신 공식 문서를 확인한다 (훈련 데이터가 오래됐을 수 있다). 확신이 있는 안정된 API까지 매번 조회할 필요는 없다. 상황별 조회 대상:

| 상황 | 조회 대상 |
|------|-----------|
| QueryDSL 동적 쿼리·`BooleanExpression` 조합 | `querydsl` |
| Spring Data JPA `Specification` / Pageable 정렬 | `spring-data-jpa` |
| Spring Security 필터 체인·메서드 보안 설정 | `spring-security` |
| SpringDoc OpenAPI 어노테이션·Swagger 커스터마이징 | `springdoc-openapi` |
| Thymeleaf 레이아웃·조각(fragment) 문법 | `thymeleaf` |
| Spring Boot 3.x 설정·자동 구성 변경사항 | `spring-boot` |

사용 순서: `resolve-library-id` → `query-docs` (토픽과 버전을 함께 지정).

## sequential-thinking — 복잡한 작업 사전 설계

다음 조건 중 하나라도 해당하면 sequential-thinking으로 단계별 계획을 먼저 수립한다.

- Controller → Service → Repository → Entity를 모두 신규 작성하는 **새 도메인 기능** 추가
- 여러 레이어·파일에 걸친 **리팩터링 또는 마이그레이션** (예: API 경로 일괄 변경)
- 원인 불명 버그의 **근본 원인 추적** (AOP·Security 필터·트랜잭션 경계 포함)
- DB 스키마 변경이 수반되는 작업 (영향 엔티티·마이그레이션 순서 정리)

계획 결과를 사용자에게 먼저 제시하고 확인받은 뒤 코드를 작성한다.

## playwright — 브라우저 UI 검증

Thymeleaf 화면을 수정하거나 새 페이지를 추가한 경우 playwright로 직접 확인한다.

**기본 접속 정보**
- URL: `http://localhost:8080`
- 로그인: 사전에 DB에 등록된 관리자 계정 (`dev` 프로파일에서 DB가 비어 있으면 `TestMemberLoader`가 `admin`/`1234` 계정을 자동 생성)
- 로그인 경로: `/admin/login`

**검증 우선순위**
1. 로그인 → 해당 화면 진입 → 핵심 기능 동작 (골든 패스)
2. 폼 유효성 검사 메시지 노출 여부
3. API 호출 후 화면 갱신(목록 reload, 성공/오류 토스트 등)
4. 다른 화면에 회귀 오류가 없는지 스크린샷으로 확인

앱이 실행 중이 아닐 때는 `./gradlew bootRun`(또는 `make dev-up`)을 먼저 실행한다.
playwright로 확인할 수 없는 환경이라면 그 사실을 명시하고 완료를 주장하지 않는다.
