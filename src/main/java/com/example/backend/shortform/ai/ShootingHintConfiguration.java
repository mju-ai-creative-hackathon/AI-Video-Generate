package com.example.backend.shortform.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** Registers a no-op provider only when no other module supplies one. */
@Configuration
public class ShootingHintConfiguration {
    @Bean
    @ConditionalOnMissingBean(ShootingHintProvider.class)
    public ShootingHintProvider noopShootingHintProvider() {
        return (userId, courseId, missionIds) -> List.of();
    }
}
