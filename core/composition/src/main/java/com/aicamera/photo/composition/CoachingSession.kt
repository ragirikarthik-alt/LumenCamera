package com.aicamera.photo.composition

import com.aicamera.photo.domain.Clock
import com.aicamera.photo.domain.CoachingEventRecord
import com.aicamera.photo.domain.CoachingIntensity
import com.aicamera.photo.domain.CoachingPhase
import com.aicamera.photo.domain.CoachingRecommendation
import com.aicamera.photo.domain.RecommendationSource
import com.aicamera.photo.domain.RecommendationType

/** Tunables per coaching intensity. */
data class CoachingPolicy(
    val minPriority: Float,
    val minConfidence: Float,
    /** Quiet time after one suggestion closes before another may appear. */
    val minGapMs: Long,
    /** Per-category cooldown after a suggestion of that type closes. */
    val categoryCooldownMs: Long,
    /** A displayed suggestion stays at least this long before it can be replaced. */
    val minStableMs: Long,
    /** A displayed suggestion that is not resolved by then expires. */
    val maxDisplayMs: Long,
    /** Replacement requires a clearly higher priority. */
    val replaceMargin: Float = 0.25f,
    /** The condition must be gone this long before the suggestion counts as achieved. */
    val achieveConfirmMs: Long = 1_200L,
) {
    companion object {
        fun forIntensity(intensity: CoachingIntensity) = when (intensity) {
            CoachingIntensity.MINIMAL -> CoachingPolicy(0.6f, 0.7f, 12_000, 60_000, 5_000, 10_000)
            CoachingIntensity.BALANCED -> CoachingPolicy(0.45f, 0.55f, 5_000, 25_000, 4_000, 12_000)
            CoachingIntensity.ACTIVE -> CoachingPolicy(0.3f, 0.5f, 2_000, 10_000, 3_000, 15_000)
        }
    }
}

/**
 * Continuous coaching state machine (spec section 12):
 *
 * NEW -> DISPLAYED -> USER_RESPONDING -> ACHIEVED
 *                  -> DISMISSED
 *                  -> EXPIRED
 *                  -> REPLACED (only by a clearly higher-priority suggestion)
 *
 * One suggestion at a time, cooldowns per category, and dismissal memory per scene.
 * Not thread-safe: drive it from a single coroutine/thread.
 */
class CoachingSession(
    private val clock: Clock = Clock.SYSTEM,
    intensity: CoachingIntensity = CoachingIntensity.BALANCED,
    private val onEvent: (CoachingEventRecord) -> Unit = {},
) {
    data class Active(
        val recommendation: CoachingRecommendation,
        val phase: CoachingPhase,
        val shownAt: Long,
        val lastSeenAt: Long,
    )

    private data class Dismissal(val type: RecommendationType, val id: String, val scene: SceneSignature?, val at: Long)

    var policy: CoachingPolicy = CoachingPolicy.forIntensity(intensity)
        private set

    var active: Active? = null
        private set

    private val cooldownUntil = mutableMapOf<RecommendationType, Long>()
    private val dismissals = mutableListOf<Dismissal>()
    private var lastClosedAt = Long.MIN_VALUE / 2
    private var lastOffers: List<CoachingRecommendation> = emptyList()
    private var currentScene: SceneSignature? = null
    private var paused = false

    fun setIntensity(intensity: CoachingIntensity) {
        policy = CoachingPolicy.forIntensity(intensity)
    }

    /** Pause while the user is doing something else (e.g. guided mode, capture review). */
    fun setPaused(value: Boolean) {
        paused = value
        if (value) active?.let { close(it, CoachingPhase.EXPIRED, cooldown = false) }
    }

    /**
     * Feed the latest opportunities (rules and/or local AI). Called every analysis tick.
     * Returns the suggestion that should be visible now, if any.
     */
    fun offer(candidates: List<CoachingRecommendation>, scene: SceneSignature?): CoachingRecommendation? {
        val now = clock.now()
        currentScene = scene
        lastOffers = candidates
        if (paused) return null

        active?.let { current ->
            val refreshed = candidates.firstOrNull { it.id == current.recommendation.id }
                ?: candidates.firstOrNull { it.type == current.recommendation.type && it.source == current.recommendation.source }
            var updated = current
            if (refreshed != null) {
                updated = current.copy(
                    // Keep the wording stable unless direction changed; refresh expiry.
                    recommendation = refreshed.copy(id = current.recommendation.id),
                    lastSeenAt = now,
                )
                active = updated
            }

            val age = now - updated.shownAt
            val goneFor = now - updated.lastSeenAt
            val isAi = updated.recommendation.source == RecommendationSource.LOCAL_AI
            when {
                // Rule-based condition disappeared for long enough -> the user fixed it.
                !isAi && goneFor >= policy.achieveConfirmMs -> {
                    close(updated, CoachingPhase.ACHIEVED)
                }
                isAi && now >= updated.recommendation.expiresAt && age >= policy.minStableMs -> {
                    close(updated, CoachingPhase.EXPIRED)
                }
                age >= maxDisplay(updated) -> close(updated, CoachingPhase.EXPIRED)
                else -> {
                    // Replacement only by a clearly higher-priority suggestion, after the stable period.
                    val challenger = eligible(candidates, now).firstOrNull { it.type != updated.recommendation.type }
                    if (challenger != null && age >= policy.minStableMs &&
                        updated.phase != CoachingPhase.USER_RESPONDING &&
                        challenger.priority >= updated.recommendation.priority + policy.replaceMargin
                    ) {
                        close(updated, CoachingPhase.REPLACED, cooldown = false)
                        show(challenger, now)
                    }
                }
            }
        }

        if (active == null && now - lastClosedAt >= policy.minGapMs) {
            eligible(candidates, now).firstOrNull()?.let { show(it, now) }
        }
        return active?.recommendation
    }

    /** User tapped "OK, doing it". */
    fun acknowledge() {
        val current = active ?: return
        if (current.phase == CoachingPhase.DISPLAYED) {
            active = current.copy(phase = CoachingPhase.USER_RESPONDING)
            emit(current.recommendation, CoachingPhase.USER_RESPONDING)
        }
    }

    /** User dismissed the suggestion: do not repeat it in this scene. */
    fun dismiss() {
        val current = active ?: return
        dismissals += Dismissal(current.recommendation.type, current.recommendation.id, currentScene, clock.now())
        close(current, CoachingPhase.DISMISSED, cooldownMultiplier = 2)
    }

    /** User asked for another idea: replace now with the next best eligible suggestion of another type. */
    fun anotherIdea(): CoachingRecommendation? {
        val now = clock.now()
        val current = active
        if (current != null) {
            close(current, CoachingPhase.REPLACED)
        }
        val next = eligible(lastOffers, now, ignoreThresholds = true)
            .firstOrNull { it.type != current?.recommendation?.type }
        if (next != null) show(next, now)
        return active?.recommendation
    }

    /**
     * Shows a suggestion the user explicitly asked for (e.g. "Ask AI"), replacing the current one.
     * Pacing rules don't apply because the user requested it.
     */
    fun showNow(rec: CoachingRecommendation): CoachingRecommendation {
        val now = clock.now()
        active?.let { close(it, CoachingPhase.REPLACED, cooldown = false) }
        show(rec, now)
        return rec
    }

    fun reset() {
        active?.let { close(it, CoachingPhase.EXPIRED, cooldown = false) }
        cooldownUntil.clear()
        dismissals.clear()
        lastClosedAt = Long.MIN_VALUE / 2
    }

    // --- internals ---

    private fun maxDisplay(a: Active) =
        if (a.phase == CoachingPhase.USER_RESPONDING) policy.maxDisplayMs * 2 else policy.maxDisplayMs

    private fun eligible(
        candidates: List<CoachingRecommendation>,
        now: Long,
        ignoreThresholds: Boolean = false,
    ): List<CoachingRecommendation> = candidates
        .asSequence()
        .filter { now < it.expiresAt }
        .filter { ignoreThresholds || (it.priority >= policy.minPriority && it.confidence >= policy.minConfidence) }
        .filter { (cooldownUntil[it.type] ?: Long.MIN_VALUE) <= now }
        .filter { !isDismissedInThisScene(it) }
        .sortedByDescending { it.priority * (0.5f + 0.5f * it.confidence) }
        .toList()

    private fun isDismissedInThisScene(rec: CoachingRecommendation): Boolean {
        val scene = currentScene
        return dismissals.any { d ->
            (d.id == rec.id || d.type == rec.type) &&
                (d.scene == null || scene == null || !scene.materiallyDifferentFrom(d.scene))
        }
    }

    private fun show(rec: CoachingRecommendation, now: Long) {
        emit(rec, CoachingPhase.NEW)
        active = Active(rec, CoachingPhase.DISPLAYED, shownAt = now, lastSeenAt = now)
        emit(rec, CoachingPhase.DISPLAYED)
    }

    private fun close(a: Active, phase: CoachingPhase, cooldown: Boolean = true, cooldownMultiplier: Int = 1) {
        val now = clock.now()
        emit(a.recommendation, phase)
        if (cooldown) cooldownUntil[a.recommendation.type] = now + policy.categoryCooldownMs * cooldownMultiplier
        lastClosedAt = now
        active = null
    }

    private fun emit(rec: CoachingRecommendation, phase: CoachingPhase) =
        onEvent(CoachingEventRecord(rec, phase, clock.now()))
}
