package com.cms.admin.log;

import com.cms.admin.log.annotation.AdminActionLogged;
import com.cms.admin.log.constant.AdminActionTypes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @AdminActionLogged 어노테이션의 actionType 값이 AdminActionTypes.ALL에 모두 포함되는지 검증.
 * Spring 컨텍스트·DB 없이 클래스패스 스캔과 리플렉션만 사용한다.
 */
class AdminActionTypeSyncTest {

    @Test
    @DisplayName("모든 @AdminActionLogged actionType이 AdminActionTypes.ALL에 포함된다")
    void 모든_어노테이션_actionType이_상수목록에_포함됨() throws ClassNotFoundException {
        // @Component 계열(@Service, @Repository 등) 클래스를 스캔
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(true);

        Set<BeanDefinition> candidates = scanner.findCandidateComponents("com.cms");
        Set<String> knownTypes = Set.copyOf(AdminActionTypes.ALL);

        List<String> violations = new ArrayList<>();

        for (BeanDefinition bd : candidates) {
            String className = bd.getBeanClassName();
            if (className == null) {
                continue;
            }
            Class<?> clazz = Class.forName(className);
            for (Method method : clazz.getDeclaredMethods()) {
                AdminActionLogged annotation = method.getAnnotation(AdminActionLogged.class);
                if (annotation != null && !knownTypes.contains(annotation.actionType())) {
                    violations.add(clazz.getSimpleName() + "#" + method.getName() +
                            " → actionType=\"" + annotation.actionType() + "\"");
                }
            }
        }

        assertThat(violations)
                .as("AdminActionTypes.ALL에 없는 actionType을 사용하는 메서드 — " +
                    "새 actionType 추가 시 AdminActionTypes.ALL에도 등록하세요: " + violations)
                .isEmpty();
    }
}
