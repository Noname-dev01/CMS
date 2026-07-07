package com.cms.admin.log;

import com.cms.admin.log.constant.AdminActionTypes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AdminActionTypes.ALL의 모든 값이 활동 로그 화면(admin/log/manage.html)의
 * ACTION_TYPE_LABELS에 라벨로 등록되어 있는지 검증.
 * Spring 컨텍스트·DB 없이 템플릿 파일을 문자열로 읽어 정규식으로 검사한다.
 *
 * 새 actionType을 AdminActionTypes.ALL에 추가했다면, 반드시
 * manage.html의 ACTION_TYPE_LABELS에도 라벨을 함께 등록해야 한다.
 * 그렇지 않으면 활동 로그 화면(필터·목록·상세 모달)에 상수 원문이 그대로 노출된다.
 */
class AdminActionTypeLabelSyncTest {

    private static final String TEMPLATE_PATH = "templates/admin/log/manage.html";

    // ACTION_TYPE_LABELS = { KEY: "값", KEY2: "값2" ... }; 블록에서 KEY만 추출
    private static final Pattern LABEL_BLOCK_PATTERN =
            Pattern.compile("ACTION_TYPE_LABELS\\s*=\\s*\\{(.*?)}", Pattern.DOTALL);
    private static final Pattern LABEL_KEY_PATTERN =
            Pattern.compile("([A-Z][A-Z0-9_]*)\\s*:");

    @Test
    @DisplayName("AdminActionTypes.ALL의 모든 값이 manage.html의 ACTION_TYPE_LABELS에 등록되어 있다")
    void 모든_액션타입이_화면_라벨맵에_등록됨() throws IOException {
        Set<String> labelKeys = extractLabelKeys(readTemplate());

        List<String> missing = new ArrayList<>();
        for (String actionType : AdminActionTypes.ALL) {
            if (!labelKeys.contains(actionType)) {
                missing.add(actionType);
            }
        }

        assertThat(missing)
                .as("ACTION_TYPE_LABELS에 없는 actionType — " + TEMPLATE_PATH +
                    "의 ACTION_TYPE_LABELS에도 라벨을 등록하세요: " + missing)
                .isEmpty();
    }

    private String readTemplate() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(TEMPLATE_PATH)) {
            assertThat(in).as(TEMPLATE_PATH + " 리소스를 찾을 수 없습니다").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Set<String> extractLabelKeys(String templateContent) {
        Matcher blockMatcher = LABEL_BLOCK_PATTERN.matcher(templateContent);
        assertThat(blockMatcher.find())
                .as(TEMPLATE_PATH + "에서 ACTION_TYPE_LABELS 블록을 찾을 수 없습니다")
                .isTrue();

        Set<String> keys = new HashSet<>();
        Matcher keyMatcher = LABEL_KEY_PATTERN.matcher(blockMatcher.group(1));
        while (keyMatcher.find()) {
            keys.add(keyMatcher.group(1));
        }
        return keys;
    }
}
