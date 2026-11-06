package com.example.backend.course.recommend;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/courses")
public class CourseRecommendationController {
    private final CourseRecommendationService service;

    public CourseRecommendationController(CourseRecommendationService service) {
        this.service = service;
    }

    public record RecommendRequest(
            @NotBlank(message = "원하는 코스를 입력해 주세요.")
            @Size(max = 500, message = "요청은 500자 이하로 입력해 주세요.")
            String prompt,
            Double lat,
            Double lng
    ) {
    }

    public record RemainingRefinementRequest(
            @NotBlank(message = "남은 일정을 어떻게 바꿀지 입력해 주세요.")
            @Size(max = 500) String prompt,
            @NotNull @Min(0) Integer completedThroughStopSeq,
            Double lat,
            Double lng
    ) {}

    @PostMapping("/{id}/remaining-refinements")
    public com.example.backend.course.dto.CourseDetail refineRemaining(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id,
            @Valid @RequestBody RemainingRefinementRequest request) {
        return service.refineRemaining(authorization, id, request.prompt(),
                request.completedThroughStopSeq(), request.lat(), request.lng());
    }

    @PostMapping("/recommendations")
    public CourseRecommendationService.RecommendResponse recommend(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody RecommendRequest request) {
        return service.recommend(authorization, request.prompt(), request.lat(), request.lng());
    }

    /** 기존 코스를 프롬프트로 고친 새 AI 코스 1개를 돌려준다. */
    @PostMapping("/{id}/refinements")
    public com.example.backend.course.dto.CourseDetail refine(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id,
            @Valid @RequestBody RecommendRequest request) {
        return service.refine(authorization, id, request.prompt(), request.lat(), request.lng());
    }
}
