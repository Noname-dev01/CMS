package com.cms.common.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.file-storage.*} 설정 프로퍼티. 프로젝트에 {@code @ConfigurationProperties}가 아직
 * 없었으나 Spring Boot 표준 관용구이고, 향후 스토리지 설정이 늘어날 가능성(허용 확장자 외부화 등)을
 * 고려해 타입 세이프한 프로퍼티 클래스로 도입한다(쟁점 9 참조).
 *
 * <p>{@code root} 기본값은 프로젝트 상대 경로({@code ./data/attachments})다. 컨테이너 전용
 * 절대경로는 이 클래스나 application-dev.yml에 박아넣지 않고 {@code docker-compose.dev.yml}의
 * {@code environment}에서 {@code APP_FILE_STORAGE_ROOT}로만 주입한다 — 그래야 로컬 {@code bootRun}과
 * 컨테이너 실행이 서로 다른 실제 경로를 쓸 수 있다(쟁점 10 참조).
 */
@ConfigurationProperties(prefix = "app.file-storage")
public class FileStorageProperties {

    /** 첨부파일이 저장될 루트 디렉터리. 존재하지 않으면 최초 저장 시 자동 생성된다. */
    private String root;

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root;
    }
}
