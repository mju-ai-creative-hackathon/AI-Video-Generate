"""파이프라인 공통 예외."""

from __future__ import annotations


class VlogError(Exception):
    """사용자에게 그대로 보여줄 수 있는 오류의 기반 클래스."""


class ConfigError(VlogError):
    pass


class NotFoundError(VlogError):
    pass


class LLMError(VlogError):
    pass


class LLMOutputError(LLMError):
    """재시도 후에도 스키마를 통과하지 못한 경우. 원본 응답을 저장한다."""


class FormatMatchError(VlogError):
    pass


class ApprovalRequiredError(VlogError):
    """승인된 스토리보드 없이 생성 단계를 시작하려 한 경우."""


class ImmutableVersionError(VlogError):
    """승인된 버전을 바꾸려 한 경우."""


class StoryboardValidationError(VlogError):
    def __init__(self, report) -> None:  # noqa: ANN001 - 순환 참조 방지
        super().__init__(report.format_errors())
        self.report = report


class WarningsNotAcceptedError(VlogError):
    def __init__(self, report) -> None:  # noqa: ANN001
        super().__init__(
            "경고가 있어 승인하지 않았습니다. 확인했다면 --accept-warnings를 붙이세요.\n"
            + report.format_warnings()
        )
        self.report = report


class StaleBaseVersionError(VlogError):
    def __init__(self, base_version: int, latest_version: int, changes: list) -> None:
        super().__init__(
            f"base_version {base_version}이 최신 버전 {latest_version}이 아니어서 가져오지 않았습니다. "
            "최신 버전을 다시 내보낸 뒤 수정하세요."
        )
        self.base_version = base_version
        self.latest_version = latest_version
        self.changes = changes


class ReapprovalRequiredError(VlogError):
    """렌더링 중 사람의 재승인이 필요한 변경이 생긴 경우 (나레이션 길이 초과 등)."""


class JobConflictError(VlogError):
    """같은 실행에 진행 중인 작업이 있어 새 작업이나 수정을 받을 수 없는 경우."""


class UploadRejectedError(VlogError):
    """지원하지 않거나 내용을 읽을 수 없는 업로드."""


class UploadTooLargeError(VlogError):
    """업로드 용량이나 개수 제한을 넘은 경우."""


class TTSError(VlogError):
    pass


class ImageError(VlogError):
    pass


class VideoError(VlogError):
    pass


class MediaError(VlogError):
    pass


class StoreError(VlogError):
    pass
