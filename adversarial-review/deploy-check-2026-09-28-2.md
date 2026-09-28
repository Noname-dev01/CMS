# PRODUCTION READINESS VERIFICATION

## Executive Summary

**Final Verdict: NO-SHIP — 검증 환경 장애로 Gate A 미완료.** 외부 배포 조건도 충족하지 않았다(실제 ingress Gate H = NOT RUN).

기준 RC는 HEAD `ec6336cb5ea3aaaa80d94efb88555e9b381da810` 및 PR6-R1/PR4-T1의 미커밋 테스트·문서 보완분이다. 제품 소스·설정·migration·dependency·backup/restore 스크립트의 HEAD 대비 변경은 없다. 기존 사용자 변경과 과거 보고서를 수정하지 않았다. 이번 검증에서 제품 결함을 새로 발견하거나 수정한 것은 아니다.

Windows clean compile / 전체 테스트 / bootJar 및 Linux builder의 bootJar는 성공했다. 그러나 **최종 runtime 이미지 생성에서 Docker BuildKit 저장소가 read-only가 되어 실패**했다. 확인 시 Windows C: 여유 공간은 **4,599,808 bytes(약 4.6MB)**였다. Docker cache 조회와 종료된 Linux 시험 컨테이너의 XML 복사도 같은 read-only 오류로 실패했다.

이 상태에서 추가 DB/컨테이너/복구 시험은 안전하지 않아 중단했다. 기존 dev 컨테이너를 정지하거나 Docker를 재시작하지 않았고, 사용자 이미지·볼륨·캐시를 임의 삭제하지 않았다. 과거 검증 결과를 이번 Gate의 PASS로 재사용하지 않았다.

## Gate Summary

| Gate | Result | Evidence | Blocking Issue |
|---|---|---|---|
| A — Build / Test / Migration | **FAIL** | Windows `clean compileJava compileTestJava test bootJar` 성공, 764개 중 763 통과 / 1 skip / 실패·오류 0. Linux builder bootJar 및 `LocalDiskFileStorageTest` 실행 명령은 성공. 새 MariaDB V1~V11 적용·Flyway validate 성공. migration 수정 이력/working diff 없음 | 최종 RC runtime 이미지 생성 실패. Linux symlink 개별 결과/skip 0 증거 XML 회수도 read-only 오류로 실패하여 대체 증거 완료 판정 불가 |
| B — Security / Browser | **NOT RUN** | 현재 UI 검증 절차 및 브라우저 도구 확인, 격리 시험 준비까지만 수행 | 최종 RC 이미지 미생성. 이번 browser/session 실기는 미실행 |
| C — Startup / Recovery | **NOT RUN** | 승인 allowlist와 prod config 확인. 전체 스위트의 기존 startup 시험은 A에 포함 | 최종 이미지·prod 설정의 11상태 독립 기동/복구 행렬 미실행 |
| D — Concurrency | **NOT RUN** | A의 전체 스위트에서 영구 MariaDB 시험 4개 통과. A-first/B-first `holder=5 waiter=6` 실제 DB lock wait 각각 기록 | Gate 전체는 미완료: 부모 비활성화↔자식 생성, 일반 수정↔일반 수정, 실제 HTTP 409+rollback 결합 검증 등 미실행. 부분 증거를 Gate PASS로 승격하지 않음 |
| E — Error Contract / Logging | **NOT RUN** | 기존 HTTP/로그 회귀 테스트는 A의 전체 스위트에 포함 | 최종 RC 및 실제 로그 수집 경로의 전체 matrix/민감정보 probe 미실행 |
| F — SMTP | **NOT RUN** | 기존 mail property/fault/token 시험은 A의 전체 스위트에 포함. compose→Spring 키 구조 확인 | 최종 `.env.prod`→container→mail Bean 값 및 정상/장애 종단 검증 미실행. 외부 사용자에게 메일 발송 없음 |
| G — Backup / Restore | **NOT RUN** | 수정된 quiesced 런북과 기존 script 계약 확인 | Docker 저장소 장애 상태에서 파괴적 drill 금지. 이번 실제 backup/restore·RTO·파괴적 실패 시험 없음 |
| H — Ingress / TLS | **NOT RUN** | 현재 prod compose는 loopback `127.0.0.1:8080`, deployment-edge 문서는 ingress 미확정. 실제 production topology/인증서/두 외부 IP 시험 환경 제공 없음 | 실제 ingress/TLS 경계 검증 전 외부 공개 불가 |

`NOT RUN`은 해당 Gate의 필수 실증을 완료하지 않았다는 뜻이다. A 안에서 관련 unit/integration test가 실행됐다는 사실과 구분한다. A의 FAIL은 제품 컴파일/테스트 실패가 아니라 **검증 환경 및 RC 이미지 산출 실패**다.

## Gate A 실행 증거

### Windows

실행: `gradlew.bat clean compileJava compileTestJava test bootJar --console=plain`.

- `BUILD SUCCESSFUL in 2m 11s`, 7 tasks executed.
- XML 집계: **tests 764 / failures 0 / errors 0 / skipped 1**.
- skip: `LocalDiskFileStorageTest.load_symlinkEscape_rejected` — Windows 환경의 symlink 생성 미지원 조건.
- `CmsApplicationTests` 로그: 2026-09-28 16:44:27 KST `Successfully validated 11 migrations`, 16:44:28 `Successfully applied 11 migrations ... v11`.
- JPA `ddl-auto=validate`로 새 DB의 애플리케이션 context도 정상 기동했다. 이는 해당 빈 DB와 엔티티의 일치 증거이며 미제공 실제 production DB의 drift 부재를 인증하는 것은 아니다.
- `git log --diff-filter=M -- src/main/resources/db/migration` 결과 없음, migration working diff 없음.
- Windows JAR SHA-256: `572E3716F326D2C2B70A7CE7331972F9526D5F859498577C291E442E8C809E3E`.

현재 실행 XML은 `build/test-results/test/`, HTML 보고서는 `build/reports/tests/test/index.html`에 있다. 이후 clean/test 실행 시 덮어써지므로 영구 Gate 증거로 보존하려면 별도 아카이브가 필요하다.

### Linux / 최종 이미지

현재 working-tree 파일을 검증용 별도 디렉터리에 복사했다(실제 env secret·portfolio·사용자 DB 제외). 기존 Dockerfile의 builder를 그대로 빌드했다.

- `cms-gate-builder:20260928`: builder `clean bootJar` 성공.
- builder image ID: `sha256:b9c4eef9e72e72c29b4222858ea6aa0992a8b9c273bc830e487ffe574129010c`.
- `docker run --name cms-gate-linux-test ... ./gradlew test --tests com.cms.common.storage.LocalDiskFileStorageTest --console=plain --no-daemon`: **BUILD SUCCESSFUL**, 컨테이너 exit 0.
- 다만 개별 symlink testcase의 실행/skip 수를 담은 XML 회수는 Docker mount의 read-only 오류로 실패했다. Linux라는 이유만으로 그 testcase가 skip 없이 실행됐다고 확정하지 않는다.
- 같은 Dockerfile 최종 stage: `COPY --from=builder /workspace/build/libs/*.jar app.jar`에서 실패. `cms-gate-rc:20260928`의 최종 이미지 생성 완료 증거 없음.

실제 오류:

```text
write /var/lib/docker/buildkit/containerd-overlayfs/metadata_v2.db: read-only file system
```

## Finding Status

| Finding | Status |
|---|---|
| C-01 | CLOSED — 이전 구현 검증 상태 유지, 이번 최종 browser Gate는 미실행 |
| H-01 | CLOSED — 이전 구현 검증 상태 유지, 최종 image startup Gate 미실행 |
| M-02 | CLOSED — 구현 상태 유지, 최종 운영 설정/SMTP Gate 미실행 |
| M-03 | CLOSED — 구현 상태 유지, 최종 RC/로그 Gate 미실행 |
| M-04 | CLOSED — 실제 MariaDB 영구 회귀 재통과, 추가 Gate D 항목은 미실행 |
| M-01 | **NOT VERIFIED** — 이번 실제 recovery Gate G 미실행 |
| M-05 | **NOT VERIFIED** — 실제 ingress Gate H 미실행 |
| M-06 | REJECTED / NO ACTION |

PR6-R1/PR4-T1의 CLOSED를 취소할 제품 회귀 증거는 없다. Finding CLOSED와 production Gate 통과는 별개다.

## Blocking Issues

### GATE-A-ENV — Docker 저장소 read-only / 물리 디스크 공간 부족

1. **실패 원인:** C: 여유 약 4.6MB와 Docker BuildKit read-only 상태를 직접 확인했다. 물리 공간 부족이 유력한 환경 원인이지만 Docker/WSL 파일시스템 내부 로그까지 확인한 인과 확정은 아니다. 제품 코드 오류로 분류하지 않는다.
2. **영향:** 최종 RC 이미지 빌드와 증거 추출 불가. 이후 Gate용 컨테이너/DB/backup 생성도 안전하게 진행할 수 없다. 실행 중인 개발 스택의 쓰기에도 영향을 줄 수 있다.
3. **최소 수정 범위:** 제품 변경 없음. 운영자가 삭제 가능한 정확한 파일·이미지·캐시 또는 Docker 데이터 위치 이동을 승인하여 호스트 물리 여유 공간을 확보한다. 이후 Docker/WSL 저장소 쓰기 정상화 여부를 확인한다. 필요 시 기존 dev 서비스 중단 영향에 대한 승인 후 Docker를 재시작한다. `docker system prune`, 전체 volume 삭제, 기존 데이터 삭제를 자동 수행하지 않는다.
4. **재검증:** 원본 Dockerfile 최종 이미지 빌드 성공 → image ID 기록 → Linux symlink testcase XML의 실제 실행/skip 0 확인 → 신규 DB migration/validate 재확인 → Gate B~G 순서 재개. 환경이 불안정했던 동안의 데이터/시험 결과를 그대로 신뢰하지 않는다.

### 필수 검증 공백

- B~G의 미실행 필수 항목은 배포 승인 차단 조건이다. 기존 보고서·단위 테스트로 대체하지 않는다.
- H는 실제 production topology가 결정되어야 실행할 수 있다. 임의 nginx/TLS 구성을 만들어 실제 ingress로 간주하지 않는다.

## Residual Risks

새 위험 수용을 결정하지 않았다. 기존에 명시된 다음 trade-off만 유지한다.

- online backup은 DB/file 같은 시점을 보장하지 않는 보조 백업이다. 정규 recovery 기준은 writer가 멈춘 quiesced backup이다.
- 같은 호스트의 백업만으로 물리 디스크/호스트 전체 유실을 복구할 수 없다.
- SMTP socket timeout은 DNS·queue 대기·전체 workflow deadline을 보장하지 않는다.

현재의 디스크 장애나 필수 Gate 미실행은 수용된 비차단 위험으로 처리하지 않는다.

## 검증 자원 / 정리

이번 생성 자원은 기존 자원과 구분한다.

- `cms-gate-linux-test`: 종료된 시험 컨테이너. Linux XML 증거가 들어 있어 **아직 보존**했다.
- `cms-gate-builder:20260928`: 이번 빌더 이미지 및 해당 build cache.
- 검증 복사본/미실행 browser harness: `C:/Users/user/.codex/visualizations/2026/09/22/01a0c7fd-e661-7ba0-8967-d738bf885e39/gates-20260928/`.
- `cms-gate-db`, `cms-gate-app`, `cms-gate-mail`, Gate 전용 network 및 destructive restore stack은 **생성하지 않았다**.
- 기존 `cms-app-dev`/`cms-db-dev`, 기존 정지 컨테이너·볼륨을 삭제·정지하지 않았다. 자동 복구를 시도하지 않고 환경 장애 상태를 보고한다.

증거 회수와 승인된 환경 복구 후 **이번 생성분만** 정리한다. 검증 자원의 미정리를 완료된 cleanup으로 표시하지 않는다.

## Final Verdict

### NO-SHIP

Gate A 실패가 해소되고 미실행 필수 Gate가 완료될 때까지 배포하지 않는다. 또한 실제 ingress가 없어 현재는 **NOT READY FOR EXTERNAL PRODUCTION** 조건에도 해당한다. 다음 단계는 제품 remediation이 아니라 **승인된 검증 환경 복구 → Gate A 재검증 → Gate B~H 재개**다.
