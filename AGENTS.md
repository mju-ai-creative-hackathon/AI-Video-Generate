# 에이전트 작업 규칙 (Claude Code · Codex 공통)

이 레포에서 작업하는 모든 AI 에이전트는 아래 규칙을 따른다.

## Git 워크플로
- **`main`에 직접 커밋·push 금지.** `main`에 push 하면 GitHub Actions가 바로 운영(EC2)에 배포한다.
- 작업마다 최신 `main`에서 브랜치를 새로 판다.
  - 이름: `feat/<작업>`, `fix/<작업>`, `docs/<작업>`, `chore/<작업>`, `ci/<작업>` (예: `feat/place-api`)
- 작업 후 브랜치를 push 하고 `main`으로 PR을 연다 (`gh pr create --base main`).
- 머지 조건 (둘 다 만족해야 함):
  1. 충돌 없음: `gh pr view <번호> --json mergeable` 이 `MERGEABLE`
  2. PR의 CI(`CI/CD` 워크플로의 build: 빌드·테스트) 통과
- 조건을 만족하면 squash 머지하고 브랜치를 지운다: `gh pr merge <번호> --squash --delete-branch`
- 충돌이 나면 머지하지 말고, 브랜치를 최신 `main`에 rebase 해서 해결한다. 판단이 필요한 충돌(남의 코드를 덮어써야 하는 경우)이면 사용자에게 먼저 물어본다.
- force push 는 본인 작업 브랜치에만 쓴다. `main`에는 절대 쓰지 않는다.

## 커밋·PR 메시지
- 한국어, Conventional Commits 형식 (`feat: ...`, `fix: ...`).
- **AI 흔적 금지**: `Co-Authored-By: Claude`, `Generated with Claude Code`, Codex 서명 등 AI 도구 표기를 커밋·PR에 넣지 않는다.

## 검증
- PR 전에 로컬에서 빌드·테스트를 통과시킨다: `./gradlew build` (Java 21)
- 배포는 머지 후 자동으로 진행된다. 배포 구성은 README의 "배포 (AWS)" 참고.
