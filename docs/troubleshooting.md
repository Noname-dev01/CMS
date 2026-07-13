# 문제 해결 기록 (Troubleshooting)

본 문서는 프로젝트 개발 중 실제로 발생한 문제와  
원인 분석 및 해결 과정을 정리한 문서입니다.

> 새 이슈는 아래 카테고리 중 적절한 곳의 마지막에 추가한다.  
> 카테고리: **개발 환경 / 인프라** · **빌드 / 의존성** · **애플리케이션 / 런타임**

---

## 개발 환경 / 인프라

Docker, WSL, 로컬 DB, IDE 관련 환경 구성 문제를 기록한다.

### WSL에서 docker 명령어를 찾을 수 없는 문제

#### 오류 메시지

```
The command 'docker' could not be found in this WSL 2 distro.
```

#### 원인

Docker Desktop에서 WSL Integration이 활성화되지 않았습니다.

#### 해결 방법

1. Docker Desktop 실행
2. Settings → Resources → WSL Integration 이동
3. 다음 항목 활성화
    - "Enable integration with my default WSL distro"
    - Ubuntu 토글 ON
4. Apply & Restart

확인:

```bash
docker version
docker compose version
```

---

### Docker 권한 오류 (docker.sock)

#### 오류 메시지

```
permission denied while trying to connect to the Docker daemon socket
```

#### 원인

현재 사용자가 docker 그룹에 포함되어 있지 않았습니다.

#### 해결 방법

```bash
sudo usermod -aG docker $USER
```

이후 Ubuntu 종료:

```powershell
exit
```

Ubuntu 재접속 후 확인:

```bash
wsl -d Ubuntu
groups
```

출력에 `docker`가 포함되어 있어야 정상입니다.

---

### menu 테이블 스키마 재생성 전 기존 데이터 확인 (2026-07-07)

#### 배경

`Menu` 엔티티는 모든 필드가 `String`으로 되어 있어(`useYn`→`Boolean`, `ord`→`Integer`, `upMenuNo`→`Long` 등) 타입 정합화가 필요하다. `application-dev.yml`의 `ddl-auto: update`는 기존 컬럼의 타입 변경·삭제를 자동 반영하지 않으므로, dev 로컬 `menu` 테이블을 drop 후 재생성하는 방식을 계획했다. 다만 dev 로컬 DB에 기존 데이터가 남아 있는지 확인 없이 drop하면 데이터 손실 위험이 있어(Codex 적대적 리뷰 지적), 재생성 착수 전 row 수를 먼저 확인했다.

#### 확인 절차 및 결과

```bash
bash scripts/dev-db.sh   # cms-db-dev 컨테이너 기동 (localhost:3307)
docker exec cms-db-dev mariadb -u admin -p1234 cms -e "SELECT COUNT(*) FROM menu;"
```

결과: `menu` 테이블 row 수 **0건** 확인.

#### 결정

row가 0건이므로 데이터 손실 우려 없이 `menu` 테이블 drop 후 재생성을 그대로 진행한다(백업·`ALTER TABLE` 전환 불필요).

---

## 빌드 / 의존성

Gradle 빌드, QueryDSL Q클래스 생성, 라이브러리 호환성 등 빌드·의존성 관련 문제를 기록한다.

---

## 애플리케이션 / 런타임

Spring Security 필터, AOP 로깅, 트랜잭션 경계, JPA/QueryDSL 동작 등 런타임 문제를 기록한다.

### @DataJpaTest 슬라이스에서 JPAQueryFactory 빈을 찾지 못해 컨텍스트 로딩 실패

#### 오류 메시지

```
NoSuchBeanDefinitionException: No qualifying bean of type
'com.querydsl.jpa.impl.JPAQueryFactory' available
  → UnsatisfiedDependencyException: Error creating bean 'adminActionLogRepositoryImpl'
  → Failed to load ApplicationContext
```

#### 원인

`@DataJpaTest`는 JPA 관련 컴포넌트(엔티티, Spring Data 리포지토리)만 로드하는 슬라이스 테스트다.
`JPAQueryFactory` 빈을 정의하는 `QuerydslConfig`는 일반 `@Configuration`이라 슬라이스 컨텍스트에 포함되지 않는다.
이 때문에 `JPAQueryFactory`에 의존하는 QueryDSL 커스텀 구현체(`*RepositoryImpl`: `MemberRepositoryImpl`, `AdminActionLogRepositoryImpl`)가 빈 생성에 실패하고, ApplicationContext 자체가 뜨지 못한다.

#### 해결 방법

테스트 클래스에 `QuerydslConfig`를 명시적으로 import 한다.

```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(QuerydslConfig.class) // *RepositoryImpl이 의존하는 JPAQueryFactory 빈을 슬라이스 컨텍스트에 포함
@ActiveProfiles("dev")
class VisitLogRepositoryDataJpaTest { ... }
```

검증:

```bash
./gradlew test --tests "com.cms.admin.visit.repository.VisitLogRepositoryDataJpaTest"
```

> 참고: 로컬 `./gradlew test`는 `.env.dev`를 자동 주입하지 않으므로 `DB_PASS` 미설정 시
> `Access denied for user 'admin'`(인증 실패)이 먼저 발생한다. 위 컨텍스트 문제와 별개이며,
> CI는 MariaDB service container에 env를 주입하므로 정상 동작한다.

---

### `th:replace`로 치환되는 `<head>` 안에 페이지 전용 CSS를 넣으면 조용히 무시됨

#### 증상

`manage.html`에 jstree CSS `<link>`와 페이지 전용 `<style>`(비활성 메뉴 회색 처리 등)을 `<head th:replace="~{admin/fragments/head :: adminHead}">` 태그 **안**에 작성했더니, 브라우저에 아무 오류도 없이 해당 CSS가 전혀 적용되지 않았다(예: 비활성 메뉴가 회색으로 표시되어야 하는데 파란색 그대로 렌더링됨).

#### 원인

Thymeleaf `th:replace`는 **호스트 엘리먼트 자체(자식 포함 전체)를 프래그먼트로 치환**한다. 즉 `<head th:replace="...">...내용...</head>`은 `...내용...` 부분이 렌더링 결과에서 통째로 사라지고 `adminHead` 프래그먼트만 남는다. `document.head.innerHTML`을 직접 확인해 jstree CSS `<link>`와 `<style>`이 렌더링 결과에 전혀 없음을 확인해 원인을 특정했다.

이미 `admin-manage.html`(회원 관리 화면)도 동일한 이유로 페이지 전용 `<link rel="stylesheet">`를 `</head>` 밖, `<body>` 시작 직후에 두는 방식으로 이 문제를 우회하고 있었다(기존 코드에 이미 존재하던 컨벤션).

#### 해결 방법

페이지 전용 `<link>`·`<style>`을 `<head th:replace="...">` **안이 아니라 `</head>` 다음, `<body>` 시작 지점**에 둔다(기존 `admin-manage.html` 컨벤션과 동일).

```html
<head th:replace="~{admin/fragments/head :: adminHead}">
    <title>메뉴 관리</title>
</head>
<body id="page-top">
<link rel="stylesheet" href="...">
<style> ... </style>
...
```

검증: `document.head.innerHTML`에 원하는 태그가 포함되는지, 또는 `getComputedStyle(el).color` 등으로 실제 스타일이 적용되는지 브라우저에서 직접 확인한다.

---

### jstree `instance.destroy()`가 컨테이너에 바인딩한 커스텀 이벤트까지 함께 제거함

#### 오류 메시지

```
TypeError: Cannot read properties of undefined (reading 'menuNo')
    at HTMLDivElement.<anonymous> ... select_node.jstree 핸들러
    at a.jstree.plugins.types.select_node (jstree.min.js)
```

#### 원인

메뉴 트리를 새로고침할 때 `instance.settings.core.data`만 바꾸고 `refresh()`를 호출하는 대신 매번 `instance.destroy()` 후 `$tree.jstree({...})`로 재초기화하도록 구현했다. 문제는 `destroy()`가 해당 컨테이너 엘리먼트(`#menuTree`)에 바인딩된 **모든** jQuery 이벤트(우리가 페이지 로드 시 한 번만 등록한 `select_node.jstree` 커스텀 핸들러 포함)를 제거한다는 점이었다. 첫 로드 직후에는 정상 동작하지만, "비활성 포함" 토글 등으로 트리를 한 번이라도 재생성한 뒤에는 노드를 클릭해도 우리 핸들러가 더 이상 호출되지 않아, jstree 내부 `trigger`가 참조하는 데이터 접근 경로가 어긋나며 위 오류가 발생했다. (실제로는 `data.node.original.data.menuNo`가 아니라 `data.node.data.menuNo`가 맞는 접근 경로였다는, 별개의 API 오용도 같은 클릭 흐름에서 함께 발견되어 같이 수정했다.)

`$._data(element, 'events')`로 바인딩된 이벤트 목록을 직접 조회해 `select_node`가 사라졌음을 확인하여 원인을 특정했다.

#### 해결 방법

트리를 재초기화하는 함수(`initTree()`) **내부에서** 매번 이벤트를 다시 바인딩한다.

```js
function initTree(data) {
    $tree.jstree({ ... });
    // destroy()가 기존 바인딩을 지우므로 재초기화할 때마다 다시 바인딩한다.
    $tree.off('select_node.jstree').on('select_node.jstree', handleSelectNode);
}
```

검증: 트리를 한 번 이상 새로고침(비활성 포함 토글 등)한 뒤 노드를 클릭해도 상세 폼이 정상적으로 채워지는지 playwright로 확인한다.

---

### `@SpringBootTest(classes = ...)` 명시 시 중첩 `@TestConfiguration`이 조용히 무시됨

#### 오류 메시지

```
Wanted but not invoked:
mailSender.send(<any org.springframework.mail.SimpleMailMessage>);
Actually, there were zero interactions with this mock.
```

`PasswordResetConcurrencyIntegrationTest.concurrentRequestWithSameEmail_onlyOneMailSent`가 간헐 실패 (플레이키).

#### 원인

테스트 안에 메일 발송 executor를 동기(`SyncTaskExecutor`)로 교체하는 중첩 `@TestConfiguration`(`SyncMailExecutorConfig`)을 두었지만, `@SpringBootTest(classes = CmsTestApplication.class)`처럼 **`classes` 속성을 명시하면 중첩 `@TestConfiguration` 자동 감지가 비활성화**된다(자동 감지는 classes/locations 미지정일 때만 동작). 그 결과 테스트 빈은 등록되지 않고 Boot 자동 구성 `applicationTaskExecutor`(비동기)가 `PasswordResetService`에 주입돼, `verify(mailSender)` 시점과 백그라운드 발송이 경합했다.

로그의 스레드명으로 원인을 특정했다: 발송 성공 로그가 `[task-1]`(applicationTaskExecutor 기본 접두사)에서 찍혀 있어 동기 교체가 적용되지 않았음을 확인.

#### 해결 방법

중첩 `@TestConfiguration` 클래스를 `classes` 배열에 **명시적으로 함께 나열**한다.

```java
@SpringBootTest(classes = {
        CmsTestApplication.class,
        PasswordResetConcurrencyIntegrationTest.SyncMailExecutorConfig.class
})
```

`applicationTaskExecutor` 자동 구성은 `@ConditionalOnMissingBean(Executor.class)`라(Boot 3.5.16 기준), 테스트 Executor 빈이 등록되면 물러나서 컨텍스트에 executor가 하나만 남는다 — 빈 이름 충돌·모호성 걱정 없이 동기 executor가 주입된다.

검증: 테스트 실행 후 리포트 XML에서 발송 성공 로그의 스레드가 executor 스레드(`task-1`)가 아니라 호출자 스레드(`pool-N-thread-M`)인지 확인한다.

---

# 정리

본 프로젝트는 단순 기능 구현뿐 아니라  
실제 개발 환경에서 발생할 수 있는 문제를 직접 경험하고 해결했습니다.
