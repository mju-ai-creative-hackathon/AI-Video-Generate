package com.example.backend.course.recommend;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/courses/recommendations")
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

    @PostMapping
    public CourseRecommendationService.RecommendResponse recommend(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody RecommendRequest request) {
        return service.recommend(authorization, request.prompt(), request.lat(), request.lng());
    }
}
