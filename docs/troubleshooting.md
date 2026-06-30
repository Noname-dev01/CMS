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

# 정리

본 프로젝트는 단순 기능 구현뿐 아니라  
실제 개발 환경에서 발생할 수 있는 문제를 직접 경험하고 해결했습니다.
