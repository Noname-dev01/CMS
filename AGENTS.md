# 저장소 가이드라인

## 프로젝트 구조 및 모듈 구성

애플리케이션 코드는 `src/main/java/com/cms`에 있습니다. 기능은 `admin/` 아래의 `member`, `menu`, `log`로 구분하며, 공통 API 코드는 `common/`, 인프라 설정은 `config/`에 둡니다. Thymeleaf 뷰는 `src/main/resources/templates`, 정적 자산은 `src/main/resources/static`에 있습니다. 테스트는 `src/test/java` 아래에서 운영 코드의 패키지 구조를 따릅니다. `build/`, `out/`, `.gradle/`, `src/main/generated/`는 생성된 결과물로 취급합니다.

## 빌드, 테스트 및 개발 명령어

- `./gradlew bootRun --args='--spring.profiles.active=dev'`는 개발 프로필로 애플리케이션을 로컬에서 실행합니다.
- `./gradlew test`는 JUnit 5 테스트 스위트를 실행합니다.
- `./gradlew clean build`는 애플리케이션을 컴파일하고 테스트한 뒤 패키징합니다.
- `make dev-db`는 IDE 기반 개발에 필요한 MariaDB만 실행합니다.
- `make dev-up` / `make dev-down`은 전체 개발용 Docker 스택을 시작하거나 중지합니다.
- `make logs`는 개발 컨테이너 로그를 실시간으로 확인합니다.

Windows에서는 `gradlew.bat`을 사용합니다. Make 대상은 WSL2 또는 다른 Bash 환경에서 사용하도록 구성되어 있습니다.

## 코딩 스타일 및 명명 규칙

Java 17, 공백 4칸 들여쓰기, 표준 Spring 규칙을 사용합니다. 패키지는 소문자, 클래스는 PascalCase, 메서드와 필드는 camelCase, 상수는 `UPPER_SNAKE_CASE`를 사용합니다. 컨트롤러는 간결하게 유지하고, 비즈니스 규칙은 서비스, 영속성 처리는 리포지토리, 요청 및 응답 모델은 `dto/request`와 `dto/response`에 둡니다. Lombok의 `@RequiredArgsConstructor`를 통한 생성자 주입을 권장합니다. 포매터나 린터가 설정되어 있지 않으므로 주변 코드의 스타일을 따르고 IDE로 import를 정리합니다.

## 테스트 가이드라인

테스트에는 JUnit 5, Spring Boot Test, Mockito, MockMvc, Spring Security Test를 사용합니다. 테스트 클래스 이름은 `*Test` 형식으로 지정하고 운영 코드와 일치하는 패키지에 배치합니다. 서비스 로직에는 단위 테스트를, 엔드포인트 및 권한 변경에는 MVC/보안 테스트를 추가합니다. 제출하기 전에 `./gradlew test`를 실행합니다. 별도의 테스트 커버리지 기준은 설정되어 있지 않습니다.

## RESTful API 규칙

- 관리자 API는 `/admin/api` 아래에 둡니다. 리소스 이름은 소문자 복수 명사를 사용하고 여러 단어로 된 경로 구간은 하이픈으로 연결합니다. 예: `/admin/api/members/{id}`. `/createMember`와 같은 동작 이름을 경로에 넣지 않습니다.
- HTTP 메서드는 의도에 맞게 사용합니다. `GET`은 조회, `POST`는 생성, `PUT`은 전체 교체 또는 하위 리소스 설정, `PATCH`는 부분 수정, `DELETE`는 삭제 또는 초기화에 사용합니다. `GET`, `PUT`, `DELETE`는 멱등성을 유지합니다.
- 일반적인 HTTP 상태 코드를 반환합니다. 조회 및 수정에는 `200 OK`, 생성에는 `Location` 헤더와 함께 `201 Created`, 응답 본문이 없는 성공에는 `204 No Content`를 사용합니다. 실패에는 의미에 따라 `400`, `401`, `403`, `404`, `409`를 사용합니다.
- JPA 엔티티 대신 DTO를 요청 및 응답에 사용합니다. 요청 본문과 쿼리 매개변수는 Bean Validation으로 검증하고, 페이지네이션, 정렬, 필터링 조건은 쿼리 매개변수로 전달합니다.
- API 오류는 `GlobalApiExceptionHandler`를 통해 처리하고 `ApiErrorResponse` 형식(`timestamp`, `path`, `code`, `message`)을 유지합니다. 스택 트레이스, 데이터베이스 상세 정보, 자격 증명은 절대 노출하지 않습니다.
- 계약을 명확히 하는 데 도움이 되는 경우 새 엔드포인트나 변경된 엔드포인트를 Springdoc 애너테이션으로 문서화합니다. 성공, 유효성 검사, 인증, 인가 및 대표적인 오류 응답을 다루는 MockMvc 테스트를 추가합니다.

## 커밋 및 풀 리퀘스트 가이드라인

최근 커밋처럼 `수정:`, `리팩터링:`, `보안:` 등의 접두사가 붙은 간결한 한국어 제목을 사용합니다. 각 커밋은 하나의 관심사로 제한하고 사용자가 확인할 수 있는 변경 사항을 설명합니다. 풀 리퀘스트에는 간단한 요약, 테스트 결과, 관련 이슈가 있다면 해당 링크를 포함하고, Thymeleaf 또는 정적 UI 변경에는 스크린샷을 첨부합니다. 보안, 스키마, 환경 또는 Docker 설정에 미치는 영향은 명확히 기술합니다.

## 보안 및 에이전트 지침

`.env.dev`의 실제 자격 증명을 커밋하지 않습니다. 변경 사항에서 명시적으로 요구하지 않는 한 CSRF 및 역할 기반 접근 제어를 유지합니다. 자동화 에이전트는 기여자와 한국어로 소통하며, 작업이 생성 파일이나 외부 제공 파일을 직접 대상으로 하지 않는 한 해당 파일을 수정하지 않습니다.

## 파일 인코딩 지침 (리뷰·분석 에이전트 필독)

- 이 저장소의 모든 소스는 **UTF-8**이며 한글 주석·문자열 리터럴을 포함합니다. Windows PowerShell 5.1로 파일을 읽을 때는 반드시 `Get-Content -Encoding utf8`을 사용합니다 (인코딩 미지정 시 CP949로 오독되어 한글이 깨집니다).
- 파일 내용에서 한글이 깨져(모지바케) 보이면 소스 손상이나 구문 오류가 아니라 **읽기 인코딩 문제**로 간주하고 UTF-8로 다시 읽습니다. 깨진 텍스트를 근거로 "닫히지 않은 문자열", "손상된 주석" 같은 결론을 내리지 않습니다.
- "컴파일 불가/빌드 실패"를 지적하기 전에 `./gradlew compileJava compileTestJava`로 실제 실패 여부를 확인합니다.
