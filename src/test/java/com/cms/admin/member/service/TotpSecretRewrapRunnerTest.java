package com.cms.admin.member.service;

import com.cms.admin.member.service.TotpSecretRewrapService.RewrapReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 기동 재암호화 runner — 어떤 실패도 기동을 막지 않고(PLAN-totp-key-rotation.md D2·결정 3), 운영자가 볼 요약 한 줄을 남긴다(결정 5).
 */
@ExtendWith(OutputCaptureExtension.class)
class TotpSecretRewrapRunnerTest {

    private final TotpSecretRewrapService service = mock(TotpSecretRewrapService.class);
    private final TotpSecretRewrapRunner runner = new TotpSecretRewrapRunner(service);

    @Test
    @DisplayName("FM4: 대상 조회 실패 같은 서비스 예외도 밖으로 내지 않고 ERROR로 남긴다(예외 타입만)")
    void swallowsServiceFailure(CapturedOutput output) {
        when(service.rewrapAll()).thenThrow(new DataAccessResourceFailureException("db down secret-detail"));

        assertThatCode(() -> runner.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
        assertThat(output).contains("TOTP 키 점검 실패").contains(DataAccessResourceFailureException.class.getName())
                .doesNotContain("secret-detail");
    }

    @Test
    @DisplayName("정상이면 INFO 요약에 현재·옛 키ID와 '현재 키로 복호 가능 T/T'가 있다")
    void logsHealthySummary(CapturedOutput output) {
        when(service.rewrapAll()).thenReturn(new RewrapReport("aaaaaaaaaaaaaaaa", Optional.of("bbbbbbbbbbbbbbbb"), 3, 2, 0, 0, 3, List.of()));

        runner.run(new DefaultApplicationArguments());

        assertThat(output).contains("INFO").contains("현재 키 ID=aaaaaaaaaaaaaaaa").contains("옛 키 ID=bbbbbbbbbbbbbbbb")
                .contains("재암호화 2건").contains("현재 키로 복호 가능 3/3");
    }

    @Test
    @DisplayName("실패가 있으면 같은 요약을 WARN으로 남긴다 — 옛 키가 없으면 '없음'")
    void logsWarnSummaryOnProblems(CapturedOutput output) {
        when(service.rewrapAll()).thenReturn(new RewrapReport("aaaaaaaaaaaaaaaa", Optional.empty(), 3, 0, 1, 0, 2, List.of(9L)));

        runner.run(new DefaultApplicationArguments());

        assertThat(output).contains("WARN").contains("옛 키 ID=없음").contains("복호 불가 1건").contains("현재 키로 복호 가능 2/3");
    }
}
