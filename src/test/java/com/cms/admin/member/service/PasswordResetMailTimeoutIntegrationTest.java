package com.cms.admin.member.service;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.net.SocketFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * SMTP socket timeout 3종(감사 M-02, remediation-plan.md PR 5)이 실제 소켓 경합에서 작동하는지
 * 검증한다. 운영 경로는 SMTP+STARTTLS이므로(v14 계획 리뷰 지적 2) read timeout은 (1) TLS 이전
 * greeting 정지, (2) STARTTLS handshake 중 정지 두 단계를 구분해서 검증하고, write timeout은
 * negotiation을 완료한 뒤 실제 write block을 유발해서 검증한다 — 다른 단계의 read timeout으로
 * 끝난 것을 write timeout 성공으로 기록하지 않기 위해 negotiation과 write block을 분리한다.
 *
 * <p>외부 SMTP·네트워크에 의존하지 않고 loopback 소켓으로 직접 만든 최소 SMTP 서버 fixture를
 * 사용한다(PasswordResetServiceTest와 동일하게 Mockito로 repository/transaction을 대신하고,
 * mailSender만 실제 {@link JavaMailSenderImpl}로 교체). read timeout 두 시나리오는
 * {@code PasswordResetService.requestReset()}을 그대로 호출해 무한 대기하지 않고 유한 시간 내
 * 종료됨과 실패 후 기존 조건부 토큰 정리 로직이 그대로 동작함을 함께 확인한다. write timeout
 * 시나리오는 실제 재설정 메일 본문(수백 바이트)만으로는 write block이 재현되지 않아
 * {@code JavaMailSenderImpl}을 직접 호출해 큰 본문으로 검증한다 — 상세 사유는 해당 테스트 주석 참조.
 */
@ExtendWith(MockitoExtension.class)
class PasswordResetMailTimeoutIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Instant FIXED_INSTANT = Instant.parse("2026-07-13T03:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZONE);
    private static final String BASE_URL = "http://localhost:8080";

    @Mock
    MemberRepository memberRepository;
    @Mock
    PasswordEncoder passwordEncoder;
    @Mock
    ApplicationEventPublisher eventPublisher;
    @Mock
    PlatformTransactionManager transactionManager;

    private ServerSocket serverSocket;

    @AfterEach
    void tearDown() throws IOException {
        if (serverSocket != null && !serverSocket.isClosed()) {
            serverSocket.close();
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("read timeout(1/2): TLS 이전 greeting 무응답은 유한 시간 내 종료되고 토큰이 정리된다")
    void readTimeout_preTls_greetingNeverSent_finishesWithinBoundedTimeAndClearsToken() throws Exception {
        int port = startFakeServer(socket -> {
            // greeting(220)을 아예 보내지 않는다 — TLS 전환 전 단계에서 정지. 이 대기 시간은
            // 반드시 아래 @Timeout(10초)보다 훨씬 길어야 한다 — 짧으면 설정된 read timeout이
            // 실제로 작동하지 않아도(회귀) "서버가 스스로 연결을 끊어서" 같은 값 안에 예외가 나
            // 테스트가 그대로 통과해버린다(code-review-loop 1라운드 지적, 수용). 서버가 절대
            // 먼저 끊지 않아야 "빨리 끝났다"는 결과가 오직 설정된 timeout 때문임을 보증한다.
            Thread.sleep(60_000);
        });

        JavaMailSenderImpl sender = fakeSender(port, 5000, 300, 5000, false);
        PasswordResetService service = serviceWith(sender);
        Member member = eligibleMember();
        given(memberRepository.findByEmailForUpdate("admin01@test.com")).willReturn(Optional.of(member));

        long elapsedMs = timedCall(() -> service.requestReset("admin01@test.com", "127.0.0.1"));

        // 300ms read timeout보다 넉넉한 상한 — "무한 대기하지 않는다"를 증명하는 것이 목적이지
        // 정확한 timeout 값 자체를 단언하는 것은 아니다(OS/CI 지연 감안).
        assertThat(elapsedMs).isLessThan(5000);
        verify(memberRepository).clearResetTokenIfMatches(eq(member.getId()), eq(member.getResetToken()));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("read timeout(2/2): STARTTLS handshake 도중 정지도 유한 시간 내 종료되고 토큰이 정리된다")
    void readTimeout_starttlsHandshakeStall_finishesWithinBoundedTimeAndClearsToken() throws Exception {
        int port = startFakeServer(socket -> {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            OutputStream out = socket.getOutputStream();

            writeLine(out, "220 fake.invalid ESMTP ready");
            readLine(in); // EHLO ...
            writeLine(out, "250-fake.invalid greets you");
            writeLine(out, "250 STARTTLS");
            readLine(in); // STARTTLS
            writeLine(out, "220 2.0.0 Ready to start TLS");
            // 여기서부터 TLS ClientHello에 응답하지 않고 침묵 — handshake 중 정지. @Timeout(10초)보다
            // 훨씬 길게 잡아, read timeout 회귀 시 서버의 자체 종료가 아니라 JUnit @Timeout이
            // 테스트를 실패시키도록 한다(code-review-loop 1라운드 지적, 수용).
            Thread.sleep(60_000);
        });

        JavaMailSenderImpl sender = fakeSender(port, 5000, 300, 5000, true);
        PasswordResetService service = serviceWith(sender);
        Member member = eligibleMember();
        given(memberRepository.findByEmailForUpdate("admin01@test.com")).willReturn(Optional.of(member));

        long elapsedMs = timedCall(() -> service.requestReset("admin01@test.com", "127.0.0.1"));

        assertThat(elapsedMs).isLessThan(5000);
        verify(memberRepository).clearResetTokenIfMatches(eq(member.getId()), eq(member.getResetToken()));
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    @DisplayName("write timeout: negotiation 완료 후 서버가 읽기를 멈추면 실제 write block이 유한 시간 내 종료된다")
    void writeTimeout_negotiationCompleteThenServerStopsReading_finishesWithinBoundedTime() throws Exception {
        // 수신 버퍼를 작게 잡아 write block을 빠르게 유발한다 — accept 전에 설정해야 상속된다.
        serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        serverSocket.setReceiveBufferSize(1024);
        int port = serverSocket.getLocalPort();

        Thread serverThread = new Thread(() -> {
            try (Socket socket = serverSocket.accept()) {
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                OutputStream out = socket.getOutputStream();

                writeLine(out, "220 fake.invalid ESMTP ready");
                readLine(in); // EHLO ...
                writeLine(out, "250 fake.invalid greets you");
                readLine(in); // MAIL FROM:<...>
                writeLine(out, "250 2.1.0 OK");
                readLine(in); // RCPT TO:<...>
                writeLine(out, "250 2.1.5 OK");
                readLine(in); // DATA
                writeLine(out, "354 Go ahead");
                // negotiation은 여기서 끝 — 이후 본문(message body)을 전혀 읽지 않고 정지한다.
                // @Timeout(15초)보다 훨씬 길게 잡아, write timeout 회귀 시 서버의 자체 종료가
                // 아니라 JUnit @Timeout이 테스트를 실패시키도록 한다(code-review-loop 1라운드 지적, 수용).
                Thread.sleep(60_000);
            } catch (IOException | InterruptedException ignored) {
                // 테스트 종료로 소켓이 닫히며 발생하는 예외는 무시 — 클라이언트 측 timeout 검증이 목적
            }
        }, "fake-smtp-server-write-timeout");
        serverThread.setDaemon(true);
        serverThread.start();

        // PasswordResetService의 실제 재설정 메일은 수백 바이트에 불과해 OS 송신 버퍼만으로도
        // write block 없이 끝난다(그 경우 다음 read에서 막혀 read timeout 시험이 돼버린다) —
        // 협상 이후 write 자체가 막히는 것을 증명하려면 버퍼를 확실히 채울 만큼 큰 본문이
        // 필요하므로, 이 시험만 JavaMailSenderImpl을 직접 호출한다(PasswordResetService의
        // 실제 발송 문구·로직은 변경하지 않는다 — Files Likely Affected 명시 범위 밖).
        // read timeout은 일부러 매우 크게(90초) 잡는다 — write timeout이 회귀로 사라지면 write
        // 완료 후 응답 대기가 read timeout(원래 5초)에 먼저 걸려 여전히 10초 미만으로 끝나
        // 회귀를 놓칠 수 있기 때문이다(code-review-loop 2라운드 지적, 수용). read timeout이
        // 90초로 밀려 있으면, write timeout이 사라졌을 때 아래 10초 상한 단언이 실패하거나
        // @Timeout(15초)이 먼저 테스트를 실패시킨다 — write timeout이 실제 작동할 때만 수 초
        // 내로 끝난다.
        JavaMailSenderImpl sender = fakeSender(port, 5000, 90_000, 300, false);
        // 서버의 작은 수신 버퍼만으로는 write block을 보장할 수 없다 — 클라이언트 OS의 송신
        // 버퍼가 2MB 본문 전체를 그대로 받아줄 만큼 크면(일부 Linux 배포판의 net.core.wmem_max
        // 기본값 등) write() 자체는 막히지 않고 DATA 응답 읽기에서만 대기하게 돼, 정상적으로
        // write timeout이 구현돼 있어도 이 테스트가 실패할 수 있다(code-review-loop 3라운드
        // 지적, 수용). 클라이언트 송신 버퍼 자체를 강제로 작게 만드는 전용 SocketFactory로
        // 플랫폼에 무관하게 write block을 보장한다.
        sender.getJavaMailProperties().put("mail.smtp.socketFactory", tinySendBufferSocketFactory(1024));
        sender.getJavaMailProperties().setProperty("mail.smtp.socketFactory.fallback", "false");
        SimpleMailMessage largeMessage = new SimpleMailMessage();
        largeMessage.setFrom("noreply@fake.invalid");
        largeMessage.setTo("admin01@test.com");
        largeMessage.setSubject("[CMS] write timeout 시험용");
        largeMessage.setText("A".repeat(2_000_000));

        long start = System.nanoTime();
        assertThatThrownBy(() -> sender.send(largeMessage)).isInstanceOf(MailSendException.class);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // write timeout(300ms)보다 넉넉한 상한 — TCP 버퍼가 실제로 찰 때까지의 여유를 감안한다.
        assertThat(elapsedMs).isLessThan(10000);
    }

    // ---- 헬퍼 ----

    @FunctionalInterface
    private interface SmtpServerScript {
        void run(Socket socket) throws IOException, InterruptedException;
    }

    private int startFakeServer(SmtpServerScript script) throws IOException {
        serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        int port = serverSocket.getLocalPort();
        Thread serverThread = new Thread(() -> {
            try (Socket socket = serverSocket.accept()) {
                script.run(socket);
            } catch (IOException | InterruptedException ignored) {
                // 테스트 종료로 소켓이 닫히며 발생하는 예외는 무시 — 클라이언트 측 timeout 검증이 목적
            }
        }, "fake-smtp-server");
        serverThread.setDaemon(true);
        serverThread.start();
        return port;
    }

    private static void writeLine(OutputStream out, String line) throws IOException {
        out.write((line + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static String readLine(BufferedReader reader) throws IOException {
        String line = reader.readLine();
        if (line == null) {
            throw new IOException("클라이언트가 연결을 닫음");
        }
        return line;
    }

    /**
     * write timeout 시험 전용 — 생성되는 모든 소켓의 송신 버퍼를 강제로 작게 잡아, OS/커널
     * 기본 송신 버퍼 크기와 무관하게 write block이 결정적으로 재현되게 한다. JavaMail은
     * {@code mail.smtp.socketFactory} 속성에 {@link SocketFactory} 인스턴스를 직접 넣으면
     * 소켓 생성에 이 팩토리를 사용한다.
     */
    private static SocketFactory tinySendBufferSocketFactory(int sendBufferSize) {
        return new SocketFactory() {
            @Override
            public Socket createSocket() throws IOException {
                Socket socket = new Socket();
                socket.setSendBufferSize(sendBufferSize);
                return socket;
            }

            @Override
            public Socket createSocket(String host, int port) throws IOException {
                Socket socket = createSocket();
                socket.connect(new InetSocketAddress(host, port));
                return socket;
            }

            @Override
            public Socket createSocket(InetAddress host, int port) throws IOException {
                Socket socket = createSocket();
                socket.connect(new InetSocketAddress(host, port));
                return socket;
            }

            @Override
            public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
                    throws IOException {
                Socket socket = createSocket();
                socket.bind(new InetSocketAddress(localHost, localPort));
                socket.connect(new InetSocketAddress(host, port));
                return socket;
            }

            @Override
            public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
                    throws IOException {
                Socket socket = createSocket();
                socket.bind(new InetSocketAddress(localAddress, localPort));
                socket.connect(new InetSocketAddress(address, port));
                return socket;
            }
        };
    }

    private JavaMailSenderImpl fakeSender(int port, int connectMs, int readMs, int writeMs, boolean starttls) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost("127.0.0.1");
        sender.setPort(port);
        Properties props = new Properties();
        props.setProperty("mail.smtp.connectiontimeout", String.valueOf(connectMs));
        props.setProperty("mail.smtp.timeout", String.valueOf(readMs));
        props.setProperty("mail.smtp.writetimeout", String.valueOf(writeMs));
        if (starttls) {
            props.setProperty("mail.smtp.starttls.enable", "true");
        }
        sender.setJavaMailProperties(props);
        return sender;
    }

    private PasswordResetService serviceWith(JavaMailSenderImpl sender) {
        return new PasswordResetService(
                memberRepository,
                passwordEncoder,
                sender,
                eventPublisher,
                new TransactionTemplate(transactionManager),
                Clock.fixed(FIXED_INSTANT, ZONE),
                new SyncTaskExecutor(),
                BASE_URL
        );
    }

    private Member eligibleMember() {
        return Member.builder()
                .id(1L)
                .userId("admin01")
                .pwd("encoded")
                .userName("홍길동")
                .email("admin01@test.com")
                .userType(Role.ROLE_ADMIN)
                .status(MemberStatus.ACTIVE)
                .createDate(NOW.minusDays(1))
                .updateDate(NOW.minusDays(1))
                .build();
    }

    private long timedCall(Runnable runnable) {
        long start = System.nanoTime();
        runnable.run();
        return (System.nanoTime() - start) / 1_000_000;
    }
}
