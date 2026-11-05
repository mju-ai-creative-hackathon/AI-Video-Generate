package com.example.backend.shortform.ai;

/** Shooting intent of one course mission, forwarded to the AI as an optional render hint. */
public record ShootingHint(Long missionId, String placeName, String kind, String title, String guide,
                           Integer durationSec) {}
