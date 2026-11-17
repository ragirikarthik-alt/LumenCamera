package com.aicamera.photo.core.ai

import com.aicamera.photo.composition.OpportunityDetector
import com.aicamera.photo.domain.Clock
import com.aicamera.photo.domain.CoachingRecommendation
import com.aicamera.photo.domain.CompositionCandidate
import com.aicamera.photo.domain.PhotographyCoach
import com.aicamera.photo.domain.SceneSnapshot

/** Deterministic coach: always available, used every analysis tick. */
class RuleBasedCoach(
    private val detector: OpportunityDetector = OpportunityDetector(),
    private val clock: Clock = Clock.SYSTEM,
) : PhotographyCoach {
    override suspend fun generateRecommendations(scene: SceneSnapshot, candidates: List<CompositionCandidate>): List<CoachingRecommendation> =
        detector.detect(scene, clock.now())
}

/**
 * Local AI coach adapter for the [PhotographyCoach] contract. Falls back to an empty list whenever
 * the model is unavailable, so callers always combine it with [RuleBasedCoach].
 */
class LocalAiCoach(
    private val coordinator: AiCoordinator,
    private val clock: Clock = Clock.SYSTEM,
) : PhotographyCoach {
    override suspend fun generateRecommendations(scene: SceneSnapshot, candidates: List<CompositionCandidate>): List<CoachingRecommendation> =
        coordinator.interpret(scene, candidates, emptyList(), clock.now())
}
