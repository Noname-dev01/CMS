package com.cms.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;
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

    @ServiceConnection
    static final MariaDBContainer<?> MARIA_DB =
            new MariaDBContainer<>(DockerImageName.parse("mariadb:10.11"));

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
