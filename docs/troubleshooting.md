# 문제 해결 기록 (Troubleshooting)

본 문서는 프로젝트 개발 중 실제로 발생한 문제와  
원인 분석 및 해결 과정을 정리한 문서입니다.

---
# 1️⃣ WSL에서 docker 명령어를 찾을 수 없는 문제

## 오류 메시지

```
The command 'docker' could not be found in this WSL 2 distro.
```

## 원인

Docker Desktop에서 WSL Integration이 활성화되지 않았습니다.

## 해결 방법

1. Docker Desktop 실행
2. Settings → Resources → WSL Integration 이동
3. 다음 항목 활성화
    - "Enable integration with my default WSL distro"
    - Ubuntu 토글 ON
4. Apply & Restart

확인:

```bash
docker version
docker compose version
```

---

# 2️⃣ Docker 권한 오류 (docker.sock)

## 오류 메시지

```
permission denied while trying to connect to the Docker daemon socket
```

## 원인

현재 사용자가 docker 그룹에 포함되어 있지 않았습니다.

## 해결 방법

```bash
sudo usermod -aG docker $USER
```

이후 Ubuntu 종료:

```powershell
exit
```

Ubuntu 재접속 후 확인:

```bash
wsl -d Ubuntu
groups
```

출력에 `docker`가 포함되어 있어야 정상입니다.


---

# 정리

본 프로젝트는 단순 기능 구현뿐 아니라  
실제 개발 환경에서 발생할 수 있는 문제를 직접 경험하고 해결했습니다.
