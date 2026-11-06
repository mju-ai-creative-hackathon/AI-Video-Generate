package com.example.backend.course.ai.gemini;

/** 장소(stopSeq)에 묶인 브이로그 촬영 미션. kind 는 "PHOTO" 또는 "VIDEO". */
public record AiMissionDraft(int stopSeq, String kind, String title, String guide, Integer durationSec) {}
