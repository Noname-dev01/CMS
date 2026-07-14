package com.cms.admin.member.service;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.common.exception.InvalidRequestException;
import com.cms.config.auth.AdminSessionRevokeEvent;
import com.cms.config.auth.LoginFailureService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 비밀번호 재설정 (메일 발송 + 토큰 검증).
 *
 * <p>비로그인 공개 흐름이므로 {@code @AdminActionLogged}(인증 컨텍스트 기준)는 사용하지 않고
 * 서비스 로그로 남긴다. 토큰(평문·해시)은 어떤 로그에도 출력 금지 — 예외 객체·메시지에
 * 메일 본문(토큰 포함)이 섞일 수 있으므로 throwable 통째 로깅도 금지한다.
 */
@Slf4j
@Service
public class PasswordResetService {

    static final Duration TOKEN_TTL = Duration.ofMinutes(30);
    static final Duration REISSUE_COOLDOWN = Duration.ofSeconds(60);
    private static final String INVALID_TOKEN_MESSAGE = "유효하지 않은 재설정 토큰입니다.";

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final JavaMailSender mailSender;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final TaskExecutor mailExecutor;
    private final String baseUrl;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 명시적 생성자 — {@code @RequiredArgsConstructor} + {@code final @Value} 필드 조합은
     * Lombok 생성자 파라미터에 {@code @Value}가 복사되지 않아 String 빈 주입 실패가 난다.
     * base-url 형식 오류는 기동 시점에 실패시켜 커밋 후 링크 생성 단계에서 처음 터지는 것을 막는다.
     */
    public PasswordResetService(MemberRepository memberRepository,
                                PasswordEncoder passwordEncoder,
                                JavaMailSender mailSender,
                                ApplicationEventPublisher eventPublisher,
                                TransactionTemplate transactionTemplate,
                                Clock clock,
                                TaskExecutor mailExecutor,
                                @Value("${app.base-url}") String baseUrl) {
        this.memberRepository = memberRepository;
        this.passwordEncoder = passwordEncoder;
        this.mailSender = mailSender;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.mailExecutor = mailExecutor;
        this.baseUrl = validateAndNormalizeBaseUrl(baseUrl);
    }

    /**
     * 발급 트랜잭션의 결과 — empty면 커밋 후 발송 단계를 실행하지 않는다.
     * 평문 토큰을 담으므로 record 기본 toString을 반드시 가려야 한다.
     */
    private record IssueResult(Long memberId, String email, String plainToken, String hashedToken) {
        @Override
        public String toString() {
            return "IssueResult{memberId=" + memberId + "}";
        }
    }

    /**
     * 재설정 메일 발송 요청. 이메일 존재 여부·계정 상태와 무관하게 항상 정상 반환한다(계정 열거 방지).
     *
     * <p>토큰 발급(트랜잭션)과 메일 발송(커밋 후)을 분리한다 — SMTP 지연이 DB 커넥션을
     * 점유하지 않도록. 같은 클래스 내부 호출은 @Transactional 프록시를 타지 않으므로
     * 경계는 TransactionTemplate으로 코드에 명시한다.
     *
     * <p>발송은 별도 executor에서 실행한다 — 발송을 응답 경로에 두면 존재 계정만 SMTP 지연이
     * 응답 시간에 실리므로, 항상 200을 반환해도 응답 시간 측정으로 계정 존재 여부가 노출된다
     * (타이밍 부채널). 발급 트랜잭션의 미세한 시간 차는 SMTP 왕복 대비 무시할 수준으로 본다.
     *
     * @param clientIp 참고 로그 전용 — 공개 엔드포인트라 위조 가능하므로 보안 판단 근거로 쓰지 않는다
     */
    public void requestReset(String email, String clientIp) {
        String normalizedEmail = normalizeEmail(email);
        log.info("비밀번호 재설정 요청 수신 email={}, ip={}", maskEmail(normalizedEmail), clientIp);

        Optional<IssueResult> issued;
        try {
            issued = transactionTemplate.execute(status -> issueToken(normalizedEmail));
        } catch (PessimisticLockingFailureException e) {
            // 락 타임아웃·데드락이 500으로 새면 "행 존재 + 경합 중" 신호가 된다 — 조용히 200 유지
            log.warn("비밀번호 재설정 발급 잠금 실패 email={}, exceptionType={}",
                    maskEmail(normalizedEmail), e.getClass().getSimpleName());
            return;
        }

        if (issued == null || issued.isEmpty()) {
            return;
        }
        dispatchResetMail(issued.get());
    }

    /**
     * 발송 작업을 executor에 제출한다. 제출 실패(풀 포화·셧다운)도 조용히 삼켜
     * 응답 균일성(200)을 유지하고, 발송 불가능해진 토큰은 조건부 클리어한다.
     */
    private void dispatchResetMail(IssueResult issued) {
        try {
            mailExecutor.execute(() -> sendResetMail(issued));
        } catch (Exception e) {
            log.error("비밀번호 재설정 메일 발송 제출 실패 memberId={}, exceptionType={}",
                    issued.memberId(), e.getClass().getSimpleName());
            clearIssuedTokenQuietly(issued);
        }
    }

    private Optional<IssueResult> issueToken(String normalizedEmail) {
        // 잠금 조회 — 쿨다운 검사~토큰 저장의 check-then-act 경합 차단
        // (이 트랜잭션의 최초 엔티티 로드라 1차 캐시 문제 없음)
        Optional<Member> found = memberRepository.findByEmailForUpdate(normalizedEmail);
        if (found.isEmpty()) {
            log.debug("비밀번호 재설정: 미존재 이메일");
            return Optional.empty();
        }
        Member member = found.get();

        LocalDateTime now = LocalDateTime.now(clock);

        // 만료된 자동 잠금(30분 경과)은 재설정 경로에서도 해제 — 로그인 폼 제출 없이 바로
        // "비밀번호 찾기"를 눌러도 동작해야 한다. 행 잠금 하라 경합 안전, 수동 잠금(lockedAt null)은 부적격 유지.
        member.releaseExpiredAutoLock(now.minus(LoginFailureService.LOCK_DURATION), now);

        // allowlist — 새 역할 추가·데이터 오염 시 발송 대상이 넓어지지 않도록 denylist 금지
        if (!isEligible(member)) {
            log.debug("비밀번호 재설정: 무자격 계정 memberId={}", member.getId());
            return Optional.empty();
        }

        // 쿨다운 — 발급 시각은 expiryAt에서 TTL 역산 (새 컬럼 불필요)
        LocalDateTime expiryAt = member.getResetTokenExpiryAt();
        if (expiryAt != null && expiryAt.isAfter(now)) {
            LocalDateTime issuedAt = expiryAt.minus(TOKEN_TTL);
            if (issuedAt.plus(REISSUE_COOLDOWN).isAfter(now)) {
                log.debug("비밀번호 재설정: 쿨다운 내 재요청 memberId={}", member.getId());
                return Optional.empty();
            }
        }

        String plainToken = generateToken();
        String hashedToken = sha256Hex(plainToken);
        member.issueResetToken(hashedToken, now.plus(TOKEN_TTL));

        return Optional.of(new IssueResult(member.getId(), member.getEmail(), plainToken, hashedToken));
    }

    /**
     * 커밋 후 발송(executor 스레드에서 실행) — 링크 생성·메시지 구성·send 전 구간이
     * 실패 처리 대상이다 (토큰은 이미 커밋됐으므로 어떤 실패든 조건부 클리어를 태운다).
     */
    private void sendResetMail(IssueResult issued) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(issued.email()); // DB 저장값 — 정규화 입력값으로 보내지 않는다
            message.setSubject("[CMS] 비밀번호 재설정 안내");
            message.setText("비밀번호 재설정을 요청하셨습니다.\n\n"
                    + "아래 링크에서 새 비밀번호를 설정해 주세요. 링크는 30분 동안 유효합니다.\n\n"
                    + buildResetLink(issued.plainToken()) + "\n\n"
                    + "본인이 요청하지 않았다면 이 메일을 무시하셔도 됩니다.");
            mailSender.send(message);
            log.info("비밀번호 재설정 메일 발송 성공 memberId={}", issued.memberId());
        } catch (Exception e) {
            // MailSendException 메시지·failedMessages에 메일 본문(토큰)이 섞일 수 있다 — throwable 통째 로깅 금지
            log.error("비밀번호 재설정 메일 발송 실패 memberId={}, exceptionType={}",
                    issued.memberId(), e.getClass().getSimpleName());
            clearIssuedTokenQuietly(issued);
        }
    }

    /**
     * 발송 실패 시 best-effort 정리 — 내가 발급한 해시와 일치할 때만 지운다.
     * 정리 자체가 실패해도 응답 균일성(200)을 깨지 않는다. 토큰은 30분 뒤 자연 만료.
     */
    private void clearIssuedTokenQuietly(IssueResult issued) {
        try {
            transactionTemplate.executeWithoutResult(status ->
                    memberRepository.clearResetTokenIfMatches(issued.memberId(), issued.hashedToken()));
        } catch (Exception e) {
            log.error("비밀번호 재설정 토큰 정리 실패 memberId={}, exceptionType={}",
                    issued.memberId(), e.getClass().getSimpleName());
        }
    }

    /**
     * 토큰으로 비밀번호를 재설정한다. 무효/만료/사용됨/상태 거부를 구분해 노출하지 않는다(동일 400).
     */
    @Transactional
    public void resetPassword(String token, String newPassword, String confirmPassword) {
        if (!newPassword.equals(confirmPassword)) {
            throw new InvalidRequestException("새 비밀번호와 비밀번호 확인이 일치하지 않습니다.");
        }

        String hashedToken = sha256Hex(token);

        // 스칼라 id 조회 — 엔티티를 로드하면 아래 잠금 재조회가 1차 캐시의 낡은 값을 돌려준다
        List<Long> ids = memberRepository.findIdsByResetToken(hashedToken);
        if (ids.isEmpty()) {
            throw new InvalidRequestException(INVALID_TOKEN_MESSAGE);
        }
        if (ids.size() > 1) {
            // 같은 해시 2행 이상 = 데이터 오염 징후 (토큰 값은 로그 금지)
            log.error("비밀번호 재설정 토큰 중복 행 감지 memberIds={}", ids);
            throw new InvalidRequestException(INVALID_TOKEN_MESSAGE);
        }
        Long memberId = ids.get(0);

        Member member;
        try {
            // 행 잠금 — 이 시점이 최초 엔티티 로드라 DB 최신 값이 보장된다
            member = memberRepository.findByIdForUpdate(memberId)
                    .orElseThrow(() -> new InvalidRequestException(INVALID_TOKEN_MESSAGE));
        } catch (PessimisticLockingFailureException e) {
            // 락 경합 노출(409)은 "유효 토큰 존재 + 사용 중" 신호 — 사유 비구분 400으로 통일.
            // 사용자에게 숨긴 DB 장애이므로 서버 로그는 필수 (토큰·throwable 금지)
            log.error("비밀번호 재설정 행 잠금 실패 memberId={}, exceptionType={}",
                    memberId, e.getClass().getSimpleName());
            throw new InvalidRequestException(INVALID_TOKEN_MESSAGE);
        }

        // 잠금 후 재검증 — 그 사이 다른 요청이 사용·클리어했으면 거부 (동시 제출 직렬화)
        if (!hashedToken.equals(member.getResetToken())) {
            throw new InvalidRequestException(INVALID_TOKEN_MESSAGE);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (member.getResetTokenExpiryAt() == null || !member.getResetTokenExpiryAt().isAfter(now)) {
            throw new InvalidRequestException(INVALID_TOKEN_MESSAGE);
        }
        // 만료된 자동 잠금은 토큰 확인 경로에서도 해제 (발급 경로와 동일 계약 — 행 잠금 하)
        member.releaseExpiredAutoLock(now.minus(LoginFailureService.LOCK_DURATION), now);
        // 토큰 발급 후 잠긴(미만료·수동)/삭제된/강등된 계정 차단
        if (!isEligible(member)) {
            throw new InvalidRequestException(INVALID_TOKEN_MESSAGE);
        }

        member.changePassword(passwordEncoder.encode(newPassword)); // reset 토큰 클리어 포함
        if (member.getStatus() == MemberStatus.PASSWORD_EXPIRED) {
            member.changeStatus(MemberStatus.ACTIVE);
        }

        // 커밋 성공 후 AFTER_COMMIT 리스너가 대상 세션을 만료한다 — 롤백 시 미소비
        eventPublisher.publishEvent(new AdminSessionRevokeEvent(member.getId()));
        log.info("비밀번호 재설정 완료 memberId={}", member.getId());
    }

    private boolean isEligible(Member member) {
        boolean statusAllowed = member.getStatus() == MemberStatus.ACTIVE
                || member.getStatus() == MemberStatus.PASSWORD_EXPIRED;
        boolean roleAllowed = member.getUserType() == Role.ROLE_ADMIN
                || member.getUserType() == Role.ROLE_MANAGER;
        return statusAllowed && roleAllowed;
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        // BigInteger.toString(16)류는 선행 0 소실로 64자 미만이 될 수 있다 — HexFormat으로 고정
        return HexFormat.of().formatHex(bytes);
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

    private String buildResetLink(String plainToken) {
        // fragment는 서버로 전송되지 않아 access log·프록시 로그·Referer에 토큰이 남지 않는다
        return baseUrl + "/admin/password-reset/confirm#token=" + plainToken;
    }

    /**
     * AdminMemberService.normalizeEmail()과 동일 규칙(private라 복제).
     * 기본 Locale의 대소문자 규칙(터키어 I/i 등)에 의존하지 않도록 Locale.ROOT 고정.
     */
    private String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    private String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "(빈 값)";
        }
        int atIndex = email.indexOf('@');
        String localPart = atIndex > 0 ? email.substring(0, atIndex) : email;
        String domainPart = atIndex > 0 ? email.substring(atIndex) : "";
        String visible = localPart.length() <= 2 ? localPart : localPart.substring(0, 2);
        return visible + "***" + domainPart;
    }

    private String validateAndNormalizeBaseUrl(String rawBaseUrl) {
        URI uri;
        try {
            uri = new URI(rawBaseUrl);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("app.base-url이 URI 형식이 아닙니다: " + rawBaseUrl, e);
        }
        boolean schemeAllowed = "http".equals(uri.getScheme()) || "https".equals(uri.getScheme());
        // 경로(context path)는 허용 — 프록시 하위 경로 배포 대비. userinfo·query·fragment는 링크 오염 위험
        if (!schemeAllowed || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalStateException(
                    "app.base-url은 userinfo/query/fragment 없는 http(s) URL이어야 합니다: " + rawBaseUrl);
        }
        return rawBaseUrl.replaceAll("/+$", "");
    }
}
