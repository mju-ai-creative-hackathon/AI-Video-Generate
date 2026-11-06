package com.example.backend.diary;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import com.example.backend.diary.domain.DiaryStatus;

public final class DiaryDtos {
    private DiaryDtos() {}
    public record Start(@NotNull @Positive Long courseId, @Size(max = 100) String title) {}
    public record Point(@NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
                        @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
                        @NotNull Instant recordedAt) {}
    public record Complete(@NotNull Instant endedAt, @NotNull @PositiveOrZero Long steps,
                           @NotNull @Size(max = 10000) List<@NotNull @Valid Point> points) {}
    public record Update(@NotBlank @Size(max = 100) String title, @NotNull @Size(max = 5000) String memo) {}
    public record Summary(UUID id, Long courseId, String courseTitle, String title, LocalDate recordDate,
                          long steps, DiaryStatus status, Instant startedAt, Instant endedAt, String footprintSvg) {}
    public record Detail(UUID id, Long courseId, String courseTitle, String title, String memo,
                         LocalDate recordDate, long steps, DiaryStatus status, Instant startedAt,
                         Instant endedAt, List<Point> points, String footprintSvg) {}
}
