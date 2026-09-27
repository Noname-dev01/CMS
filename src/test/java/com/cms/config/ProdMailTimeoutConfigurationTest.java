package com.cms.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.util.PropertyPlaceholderHelper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SMTP socket timeout 3종(감사 M-02, remediation-plan.md PR 5)의 설정 전달 계약을 검증한다.
 *
 * <p>이 프로젝트의 기존 관례(AdminBootstrapStartupIntegrationTest 등)를 따라 실제 {@code prod}
 * 프로파일로 전체 Spring 컨텍스트를 띄우지 않는다 — YAML placeholder 해석은
 * {@link YamlPropertySourceLoader}+{@link PropertyPlaceholderHelper}로 직접 검증하고,
 * "이 값이 실제 {@code JavaMailSenderImpl}에 반영되는가"는 {@link MailSenderAutoConfiguration}만
 * 올린 좁은 {@link ApplicationContextRunner}로 검증한다. 두 검증을 합치면 DB·부트스트랩 등
 * 무관한 컨텍스트 없이도 "application-prod.yml의 값 → 실제 Bean 속성"의 전체 경로가 증명된다.
 */
class ProdMailTimeoutConfigurationTest {

    private static final PropertyPlaceholderHelper PLACEHOLDER_HELPER =
            new PropertyPlaceholderHelper("${", "}", ":", true);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
            .withPropertyValues(
                    "spring.mail.host=smtp.example.invalid",
                    "spring.mail.properties.mail.smtp.auth=true",
                    "spring.mail.properties.mail.smtp.starttls.enable=true");

    // ---- YAML placeholder 계약 (Implementation Steps 1) ----

    @Test
    @DisplayName("application-prod.yml의 SMTP timeout 3종은 env 미설정 시 10000/30000/30000으로 해석된다")
    void yamlDefaults_resolveTo10000_30000_30000_whenEnvUnset() throws Exception {
        Properties rawMailProps = loadProdYamlMailProperties();

        assertThat(rawMailProps.getProperty("mail.smtp.connectiontimeout"))
                .isEqualTo("${MAIL_SMTP_CONNECTION_TIMEOUT_MS:10000}");
        assertThat(rawMailProps.getProperty("mail.smtp.timeout"))
                .isEqualTo("${MAIL_SMTP_READ_TIMEOUT_MS:30000}");
        assertThat(rawMailProps.getProperty("mail.smtp.writetimeout"))
                .isEqualTo("${MAIL_SMTP_WRITE_TIMEOUT_MS:30000}");

        // env 변수가 전혀 없다고 가정한 resolver(항상 null 반환) — placeholder의 ":" 뒤 기본값으로 귀결돼야 한다.
        PropertyPlaceholderHelper.PlaceholderResolver noEnv = name -> null;
        assertThat(PLACEHOLDER_HELPER.replacePlaceholders(
                rawMailProps.getProperty("mail.smtp.connectiontimeout"), noEnv)).isEqualTo("10000");
        assertThat(PLACEHOLDER_HELPER.replacePlaceholders(
                rawMailProps.getProperty("mail.smtp.timeout"), noEnv)).isEqualTo("30000");
        assertThat(PLACEHOLDER_HELPER.replacePlaceholders(
                rawMailProps.getProperty("mail.smtp.writetimeout"), noEnv)).isEqualTo("30000");
    }

    @Test
    @DisplayName("환경변수가 설정되면 YAML placeholder는 기본값이 아닌 override 값으로 해석된다")
    void yamlPlaceholder_envOverride_resolvesToOverrideValue() throws Exception {
        Properties rawMailProps = loadProdYamlMailProperties();

        PropertyPlaceholderHelper.PlaceholderResolver overrideEnv = name -> switch (name) {
            case "MAIL_SMTP_CONNECTION_TIMEOUT_MS" -> "5000";
            case "MAIL_SMTP_READ_TIMEOUT_MS" -> "15000";
            case "MAIL_SMTP_WRITE_TIMEOUT_MS" -> "20000";
            default -> null;
        };

        assertThat(PLACEHOLDER_HELPER.replacePlaceholders(
                rawMailProps.getProperty("mail.smtp.connectiontimeout"), overrideEnv)).isEqualTo("5000");
        assertThat(PLACEHOLDER_HELPER.replacePlaceholders(
                rawMailProps.getProperty("mail.smtp.timeout"), overrideEnv)).isEqualTo("15000");
        assertThat(PLACEHOLDER_HELPER.replacePlaceholders(
                rawMailProps.getProperty("mail.smtp.writetimeout"), overrideEnv)).isEqualTo("20000");
    }

    // ---- YAML ↔ compose 기본값 정합성 (Implementation Steps 2, v14 계획 리뷰 지적 5) ----

    @Test
    @DisplayName("docker-compose.prod.yml의 fallback 기본값은 application-prod.yml의 기본값과 일치한다")
    void composeDefaults_matchYamlDefaults() throws Exception {
        Properties yamlProps = loadProdYamlMailProperties();
        String composeText = Files.readString(Path.of("docker-compose.prod.yml"));

        assertThat(composeDefaultOf(composeText, "MAIL_SMTP_CONNECTION_TIMEOUT_MS"))
                .isEqualTo(yamlDefaultOf(yamlProps.getProperty("mail.smtp.connectiontimeout")));
        assertThat(composeDefaultOf(composeText, "MAIL_SMTP_READ_TIMEOUT_MS"))
                .isEqualTo(yamlDefaultOf(yamlProps.getProperty("mail.smtp.timeout")));
        assertThat(composeDefaultOf(composeText, "MAIL_SMTP_WRITE_TIMEOUT_MS"))
                .isEqualTo(yamlDefaultOf(yamlProps.getProperty("mail.smtp.writetimeout")));
    }

    // ---- 실제 Spring Boot mail 자동 구성 바인딩 (Implementation Steps 4) ----

    @Test
    @DisplayName("세 timeout 속성은 실제 JavaMailSenderImpl.javaMailProperties에 그대로 반영된다")
    void javaMailSenderImpl_reflectsConfiguredTimeouts() {
        contextRunner
                .withPropertyValues(
                        "spring.mail.properties.mail.smtp.connectiontimeout=10000",
                        "spring.mail.properties.mail.smtp.timeout=30000",
                        "spring.mail.properties.mail.smtp.writetimeout=30000")
                .run(context -> {
                    JavaMailSenderImpl sender = context.getBean(JavaMailSenderImpl.class);
                    Properties props = sender.getJavaMailProperties();
                    assertThat(props.getProperty("mail.smtp.connectiontimeout")).isEqualTo("10000");
                    assertThat(props.getProperty("mail.smtp.timeout")).isEqualTo("30000");
                    assertThat(props.getProperty("mail.smtp.writetimeout")).isEqualTo("30000");
                });
    }

    @Test
    @DisplayName("override 값도 실제 JavaMailSenderImpl.javaMailProperties에 그대로 반영된다")
    void javaMailSenderImpl_reflectsOverriddenTimeouts() {
        contextRunner
                .withPropertyValues(
                        "spring.mail.properties.mail.smtp.connectiontimeout=5000",
                        "spring.mail.properties.mail.smtp.timeout=15000",
                        "spring.mail.properties.mail.smtp.writetimeout=20000")
                .run(context -> {
                    JavaMailSenderImpl sender = context.getBean(JavaMailSenderImpl.class);
                    Properties props = sender.getJavaMailProperties();
                    assertThat(props.getProperty("mail.smtp.connectiontimeout")).isEqualTo("5000");
                    assertThat(props.getProperty("mail.smtp.timeout")).isEqualTo("15000");
                    assertThat(props.getProperty("mail.smtp.writetimeout")).isEqualTo("20000");
                });
    }

    @Test
    @DisplayName("잘못된 값(단위 문자열)도 Spring이 거부하지 않고 원문 그대로 통과시킨다 — 그래서 Gate F의 수동 확인이 필요하다")
    void javaMailSenderImpl_doesNotValidateInvalidValue_passesRawStringThrough() {
        contextRunner
                .withPropertyValues("spring.mail.properties.mail.smtp.connectiontimeout=30s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    JavaMailSenderImpl sender = context.getBean(JavaMailSenderImpl.class);
                    // Spring/JavaMail 어느 쪽도 이 시점에는 정수 여부를 검증하지 않는다 — 잘못된 값이
                    // 조용히 JavaMail 자체 기본값(무한대)으로 되돌아갈 수 있는 이유다(v14 계획 리뷰 지적 1).
                    assertThat(sender.getJavaMailProperties().getProperty("mail.smtp.connectiontimeout"))
                            .isEqualTo("30s");
                });
    }

    // ---- Gate F 수동 식별 절차 고정 (Tests to Add, v14 계획 리뷰 지적 1) ----

    @ParameterizedTest(name = "\"{0}\" → valid={1}")
    @CsvSource({
            "1, true",
            "10000, true",
            "2147483647, true",
            "0, false",
            "-1, false",
            "30s, false",
            "'', false",
            "' 10000', false",
            "3.5, false",
            "99999999999, false",
            "007, false",
    })
    @DisplayName("Gate F가 배포를 중단해야 하는 잘못된 override 값을 식별하는 절차를 고정한다")
    void gateFValidationFixture_identifiesInvalidValues(String candidate, boolean expectedValid) {
        assertThat(isValidTimeoutMs(candidate)).isEqualTo(expectedValid);
    }

    /**
     * Gate F("최종 실행 환경의 connection/read/write 속성이 모두 유한한 양의 정수 ms")가 배포자에게
     * 요구하는 수동 판정 절차를 테스트 형태로 고정한 것 — 제품 코드에 validator를 추가하지 않기로
     * 한 계획(v14 계획 리뷰 지적 1)에 따라 이 메서드는 프로덕션 코드가 아니라 이 테스트 전용이다.
     */
    private static boolean isValidTimeoutMs(String candidate) {
        if (candidate == null) {
            return false;
        }
        if (!candidate.equals(candidate.trim())) {
            return false;
        }
        if (!candidate.matches("[1-9][0-9]*")) {
            return false;
        }
        try {
            long value = Long.parseLong(candidate);
            return value <= Integer.MAX_VALUE;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // ---- 헬퍼 ----

    private Properties loadProdYamlMailProperties() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load("application-prod",
                new ClassPathResource("application-prod.yml"));

        Properties result = new Properties();
        for (PropertySource<?> source : sources) {
            EnumerablePropertySource<?> enumerable = (EnumerablePropertySource<?>) source;
            for (String name : enumerable.getPropertyNames()) {
                if (name.startsWith("spring.mail.properties.mail.smtp.")) {
                    String shortKey = name.substring("spring.mail.properties.".length());
                    result.setProperty(shortKey, String.valueOf(enumerable.getProperty(name)));
                }
            }
        }
        return result;
    }

    private static String composeDefaultOf(String composeText, String envVarName) {
        // ${VAR:-default} 형태 — docker-compose.prod.yml의 선택 환경변수 fallback 문법.
        Pattern pattern = Pattern.compile(Pattern.quote("${" + envVarName + ":-") + "(\\d+)\\}");
        Matcher matcher = pattern.matcher(composeText);
        assertThat(matcher.find())
                .as("docker-compose.prod.yml에서 %s의 ${VAR:-default} 패턴을 찾지 못함", envVarName)
                .isTrue();
        return matcher.group(1);
    }

    private static String yamlDefaultOf(String rawPlaceholderValue) {
        // ${VAR:default} 형태 — application-prod.yml의 선택 환경변수 fallback 문법.
        Matcher matcher = Pattern.compile(":(\\d+)\\}$").matcher(rawPlaceholderValue);
        assertThat(matcher.find())
                .as("application-prod.yml 값 '%s'에서 기본값을 추출하지 못함", rawPlaceholderValue)
                .isTrue();
        return matcher.group(1);
    }
}
