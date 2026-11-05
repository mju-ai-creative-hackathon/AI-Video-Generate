package com.example.backend.course;

import com.example.backend.course.dto.CourseDetail;
import com.example.backend.course.dto.CourseSaveResponse;
import com.example.backend.course.dto.CourseSummary;
import com.example.backend.place.dto.PageResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/courses")
public class CourseController {
    private final CourseService courseService;

    public CourseController(CourseService courseService) {
        this.courseService = courseService;
    }

    @GetMapping("/regions")
    public List<String> regions() {
        return courseService.getRegions();
    }

    @GetMapping
    public PageResponse<CourseSummary> courses(
            @RequestParam(required = false) String region,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return courseService.getCourses(region, lat, lng, page, size, authorization);
    }

    @GetMapping("/saved")
    public PageResponse<CourseSummary> saved(
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return courseService.getSaved(lat, lng, page, size, authorization);
    }

    @GetMapping("/{id}")
    public CourseDetail course(
            @PathVariable Long id,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return courseService.getCourse(id, lat, lng, authorization);
    }

    @PutMapping("/{id}/save")
    public CourseSaveResponse save(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return courseService.save(id, authorization);
    }

    @DeleteMapping("/{id}/save")
    public CourseSaveResponse unsave(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return courseService.unsave(id, authorization);
    }
}
