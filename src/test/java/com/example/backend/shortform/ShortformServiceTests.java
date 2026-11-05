package com.example.backend.shortform;

import com.example.backend.auth.AuthService;
import com.example.backend.auth.dto.UserResponse;
import com.example.backend.common.ApiException;
import com.example.backend.shortform.ai.AiRenderClient;
import com.example.backend.shortform.storage.MediaStorage;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShortformServiceTests {
    @Test void absentAiConfigurationDoesNotQueueFakeWork() {
        var projects = mock(ShortformProjectRepository.class);
        var jobs = mock(RenderJobRepository.class);
        var auth = mock(AuthService.class);
        var ai = mock(AiRenderClient.class);
        var service = new ShortformService(projects, mock(ShortformAssetRepository.class), jobs, auth, mock(MediaStorage.class), ai);
        UUID ownerId = UUID.randomUUID(), projectId = UUID.randomUUID(), key = UUID.randomUUID();
        var project = new ShortformProject(ownerId, "서울 여행"); project.id = projectId;
        when(auth.getCurrentUser("Bearer test")).thenReturn(new UserResponse(ownerId, "user@example.com", "테스트", null, Instant.now()));
        when(projects.findLockedById(projectId)).thenReturn(Optional.of(project));
        when(jobs.findByProjectIdAndIdempotencyKey(projectId, key)).thenReturn(Optional.empty());
        var request = new ShortformDtos.RenderRequest(VideoStyle.VLOG, VideoMood.URBAN, null,
                java.util.stream.IntStream.range(0, 5).mapToObj(i -> UUID.randomUUID()).toList());
        var error = assertThrows(ApiException.class, () -> service.render("Bearer test", projectId, key, request));
        assertEquals(503, error.getStatus().value());
        assertEquals("AI_NOT_CONFIGURED", error.getCode());
        verify(jobs, never()).save(any());
    }
}
