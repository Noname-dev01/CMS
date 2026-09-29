# Dependabot 운영 가이드

`.github/dependabot.yml`이 하는 일과 이 프로젝트에서의 운영 방식을 정리한다(감사 M-06 CI 배포 게이트의 일부, `docs/deployment.md` "CI 배포 게이트"·`adversarial-review/plan/PLAN-ci-prod-gates.md` 참조).

## Dependabot이란

GitHub에 내장된 **의존성 자동 갱신 봇**이다. 저장소의 설정 파일(`.github/dependabot.yml`)을 읽고, 정해진 주기마다 의존성의 새 버전을 확인해 **갱신 PR을 대신 만들어 준다.** 코드를 직접 머지하지는 않으며 PR만 열어 둔다. 머지는 사람이 한다.

## 동작 순서

1. 설정된 주기(이 프로젝트는 주 1회)에 각 생태계의 파일을 읽는다.
2. 현재 버전보다 새 버전이 있으면 그 버전으로 바꾼 브랜치를 만들고 PR을 연다.
3. PR이 열리면 CI(`test`, `prod-smoke`)가 자동으로 돌아 "올려도 되는지" 결과가 PR에 표시된다.
4. 사람이 확인해 머지하거나 닫는다. 닫은 버전은 다시 열지 않지만, 더 새 버전이 나오면 새 PR을 연다.

## 이 프로젝트가 감시하는 곳

| 생태계 | 보는 파일 | 하는 일 | 소음 관리 |
|---|---|---|---|
| `docker` | `Dockerfile` | `eclipse-temurin` 베이스 이미지 상향 PR(digest도 함께 갱신) | 메이저 상향 무시 |
| `docker-compose` | `docker-compose.*.yml` | `mariadb` 이미지 상향 PR | 메이저 상향 무시 |
| `github-actions` | `.github/workflows/*.yml` | `actions/checkout` 등 액션 버전 상향 PR | 메이저 무시 안 함, **한 PR로 그룹핑** |
| `gradle` | `build.gradle`, Gradle wrapper | Spring Boot, springdoc 등 상향 PR(동시 5개까지) | 메이저 상향 무시(wrapper 포함) |

### 소음 관리 결정 (2026-09-29)

- **메이저 상향은 자동 PR로 받지 않는다.** Spring Boot 4, Java 25, MariaDB 13 같은 이행은 코드·설정 변경이 따르는 별도 프로젝트라서 자동 PR 대상이 아니다. 첫 실행에서 이런 PR 4개가 올라왔고 CI가 전부 실패시켰다. 마이너·패치 상향과 같은 태그의 digest 갱신은 계속 받는다.
- **Gradle wrapper의 메이저 상향(8→9)도 자동으로 받지 않는다.** 필요하면 사람이 직접 시작한다.
- **GitHub Actions는 예외다.** 액션 버전이 `v4`→`v7`처럼 메이저 태그라서 메이저를 무시하면 갱신이 전부 막힌다. 대신 한 PR로 묶어 개수를 줄인다.

## 도입 이유

M-06에서 컨테이너 이미지를 digest(`image:tag@sha256:...`)로 고정했다. digest 고정은 같은 커밋에서 같은 이미지를 쓰게 해 재현성을 주지만, **보안 패치가 자동으로 들어오지 않는다**는 단점이 있다. Dependabot이 그 갱신을 PR로 대신 제안해 이 단점을 상쇄한다.

## 한계

- **Java 소스 안의 문자열은 추적하지 못한다.** `MariaDbContainerSupport`의 `mariadb@sha256:...`와 `scripts/prod-backup.sh`·`scripts/prod-restore.sh`의 이미지 참조는 갱신 대상이 아니다. compose만 바뀐 PR은 `scripts/ci/check-image-refs.sh`가 digest 불일치로 CI를 실패시키므로, 그 PR에서 나머지 mariadb 참조를 같은 digest로 직접 맞춰야 한다(`docs/deployment.md` "이미지 digest 갱신").
- **마이너 상향도 호환성을 보장하지 않는다.** 메이저를 무시해도 마이너 PR은 올라오므로 CI 결과로 판단해야 한다.
- **머지는 자동이 아니다.** 사람이 눌러야 한다. 다만 브랜치 보호의 필수 체크에 `test`와 `prod-smoke`가 등록돼 있어(2026-09-29) `prod-smoke`가 실패한 갱신 PR은 머지할 수 없다(`docs/branching.md`).

## 갱신 PR 처리 원칙

1. **CI 결과부터 본다.** `test`와 `prod-smoke`가 모두 통과해야 머지 후보다. 실패하면 로그로 원인을 확인한다.
2. **이미지 PR은 참조 일치를 맞춘다.** `mariadb` compose PR은 스크립트·테스트 리터럴을 같은 digest로 함께 수정한다.
3. **Boot BOM 오버라이드 확인.** `build.gradle`의 `ext['tomcat.version']`·`ext['jackson-bom.version']`은 Spring Boot가 그 버전 이상을 관리하게 되면 제거한다(파일 주석 참조). Boot 3.5.x 패치 PR이 올라오면 그때 확인한다.
4. **필요 없는 PR은 닫는다.** 닫을 때 댓글로 `@dependabot ignore this major version`을 달면 그 메이저를 이후로 무시한다(설정 파일을 바꾸지 않고 PR 단위로 쓸 수 있는 수단).

## 첫 실행 스냅샷과 처리 결과 (2026-09-29)

`dependabot.yml`이 머지되자 밀려 있던 업데이트가 한꺼번에 9개 PR로 열렸다.

| PR | 내용 | CI(`prod-smoke` / `test`) | 처리 |
|---|---|---|---|
| #48 | `actions/upload-artifact` 4→7 | pass / pass | 머지 |
| #51 | `actions/setup-java` 4→6 | pass / pass | 머지 |
| #54 | `actions/checkout` 4→7 | pass / pass | 머지 |
| #55 | `actions/cache` 4→6 | pass / pass | 머지 |
| #56 | gradle-wrapper 8.12.1→9.8.0 | pass / pass | 열어 둠(빌드 도구 메이저라 별도 검토) |
| #49 | Spring Boot 3.5.16→4.1.1 | fail / fail | 닫음 + 메이저 무시 댓글 |
| #52 | springdoc 2.8.14→3.1.1 | fail / fail | 닫음 + 메이저 무시 댓글 |
| #50 | `eclipse-temurin` 17-jre→25-jre | fail / pass | 닫음 + 메이저 무시 댓글 |
| #53 | `mariadb` 10.11→13.0 | fail / pass | 닫음 + 메이저 무시 댓글 |

실패한 4건의 원인은 로그로 확인하지 않았고 다음은 추정이다: #49·#52는 Boot 4 이행이 필요한 상향, #50은 런타임 JRE만 25로 올라 빌더 JDK 17과 불일치, #53은 compose만 바뀌어 `check-image-refs.sh` 일치 검사에 걸림. 새 게이트가 위험한 메이저 상향을 실제로 걸러낸 사례다.
