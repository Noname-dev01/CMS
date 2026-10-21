package com.cms.support;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mariadb.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * DB에 실제로 접속하는 테스트가 상속하는 베이스 클래스.
 *
 * <p>static 초기화 블록으로 컨테이너를 직접 시작한다({@code @Testcontainers}/{@code @Container}
 * 미사용) — {@code @Container} static 필드는 "테스트 클래스 종료 후 정지"되므로 여러 클래스가
 * 상속할 경우 클래스마다 재기동되어 전환 목적(속도)이 무너진다. 대신 JVM 생애주기 동안
 * 살아있는 진짜 싱글턴으로 두고, Testcontainers Ryuk이 JVM 종료 시 회수하게 한다.
 *
 * <p>테스트 JVM(워커)당 컨테이너 1개를 보장하려면 build.gradle의 {@code maxParallelForks=1}
 * + {@code forkEvery=0}이 함께 필요하다. 컨테이너 시작 시점의 ID를 로그로 남기는데, 이는
 * (a) 실행마다 컨테이너 ID가 다른지 대조하고 (b) JVM 종료 후 해당 ID가 실제로 제거됐는지
 * ({@code docker ps -a --filter id=<ID>}) 확인하는 두 검증의 근거가 된다. 이 로그가 빌드
 * 콘솔에 노출되려면 build.gradle의 {@code testLogging.showStandardStreams=true}가 필요하다.
 */
public abstract class MariaDbContainerSupport {

    private static final Logger log = LoggerFactory.getLogger(MariaDbContainerSupport.class);

    // protected: 스프링 컨텍스트 없이 컨테이너에 직접 접속하는 테스트(Flyway 업그레이드 경로 등)가 접속 정보를 읽는다
    @ServiceConnection
    protected static final MariaDBContainer MARIA_DB =
            new MariaDBContainer(
                    DockerImageName.parse("mariadb@sha256:7db29378d4fdab73f8123bbc2b48905c90d1a4b00cf848b028f1e81e623257f2")
                            .asCompatibleSubstituteFor("mariadb"))
                    // 기본 max_connections(151)는 캐시된 스프링 컨텍스트마다 쥐는 Hikari 풀(최대 10)의 합이 넘기 쉽다 — 컨텍스트가 하나 늘어난 변경
                    // (게시글 예약 게시의 MutableClock 컨텍스트, 2026-10-11)이 전체 실행에서만 "Too many connections"를 내 LockWaitObserverTest 5건을
                    // 깨뜨렸다(단독 실행은 통과). 컨텍스트 수에 여유를 두고 한도를 올린다.
                    .withCommand("--max-connections=500")
                    // 데이터 디렉터리를 메모리(tmpfs)에 둔다 — 일회용 컨테이너라 내구성이 필요 없고, 디스크 I/O가 DDL 비용을 지배해
                    // V1부터 다시 적용하는 마이그레이션 시험이 약 6배 빨라졌다(2026-10-21 실측, SiteMenuMergeMigrationTest 113초 → 18초).
                    // InnoDB 내구성 옵션(flush_log_at_trx_commit=0·doublewrite=0)은 tmpfs 위에서 추가 효과가 없어 넣지 않았다.
                    // 크기를 지정하지 않으면 상한이 Docker VM 메모리의 절반이라 1g로 묶는다 — 전체 실행 최대 사용량은 426MB였다(같은 날
                    // 실측, 스키마를 DROP해도 InnoDB 공용 파일은 줄지 않아 실행 내내 증가). 넘치면 "디스크 가득 참"으로 실패하니 그때 올린다.
                    .withTmpFs(Map.of("/var/lib/mysql", "rw,size=1g"));

    static {
        MARIA_DB.start();
        log.info("MariaDB 테스트 컨테이너 시작: containerId={}", MARIA_DB.getContainerId());
    }

    @DynamicPropertySource
    static void mailProperties(DynamicPropertyRegistry registry) {
        // host/port까지 명시 — username/password만 채우면 JavaMailSender mock이 빠진
        // 테스트에서 실제 smtp.gmail.com:587로 나가 네트워크 타임아웃이라는 별도 실패 모드가
        // 생긴다. localhost 미사용 포트로 돌려 mock이 빠져도 즉시 connection refused로 fail-fast.
        registry.add("spring.mail.host", () -> "localhost");
        registry.add("spring.mail.port", () -> "1");
        registry.add("spring.mail.username", () -> "test@example.com");
        registry.add("spring.mail.password", () -> "test");
    }
}
