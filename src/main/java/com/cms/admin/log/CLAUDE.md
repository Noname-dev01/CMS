# CLAUDE.md — com.cms.admin.log

이 디렉터리(관리자 행위 감사 로그 도메인) 작업 시에만 로드된다. 공통 규칙은 프로젝트 루트 `CLAUDE.md` 참조.

**AdminActionLog**: 관리자 행위 감사 로그. `@AdminActionLogged`가 붙은 메서드 호출 시 성공/실패·요청 IP·URI가 자동 기록된다. `AdminActionLogAspect`가 처리한다.

- **독립 트랜잭션**: `AdminActionLogService.log()`는 `Propagation.REQUIRES_NEW`로 실행된다. 원 비즈니스 트랜잭션이 롤백되어도 FAIL 로그는 별도 트랜잭션으로 커밋된다.
- **예외 격리**: Aspect 내부에서 로그 저장 실패를 try-catch로 격리한다. 로그 저장이 실패해도 원 요청 결과(성공/실패)는 뒤집히지 않는다.
