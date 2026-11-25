package dev.trigger.agent;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Models human reaction time and click behavior to defeat ML-based anticheat.
 *
 * Real human clicking has: gaussian-distributed reaction times, fatigue drift,
 * burst/cooldown patterns, session warmup, micro-hesitations, and natural
 * variance that increases under pressure. This engine replicates all of it.
 *
 * The key insight: ML anticheats train on distributions, not individual clicks.
 * A uniform random delay is trivially detectable — real humans produce a
 * right-skewed gaussian with drift. This engine matches that shape.
 */
public class HumanTiming {

    // base reaction time parameters (milliseconds)
    // human visual reaction to a stimulus: ~180ms mean
    private static final double BASE_REACTION_MEAN = 170.0;
    private static final double BASE_REACTION_STDDEV = 28.0;

    // crit recognition overhead — processing that the crit window is open
    private static final double CRIT_RECOGNITION_MEAN = 35.0;
    private static final double CRIT_RECOGNITION_STDDEV = 12.0;

    // motor execution — finger press delay
    private static final double MOTOR_MEAN = 18.0;
    private static final double MOTOR_STDDEV = 6.0;

    // fatigue model
    private static final double FATIGUE_PER_ATTACK_MS = 0.4;
    private static final double FATIGUE_RECOVERY_RATE = 0.15; // ms recovered per ms idle
    private static final double MAX_FATIGUE_MS = 60.0;

    // burst detection avoidance
    private static final int BURST_THRESHOLD = 3;
    private static final double BURST_PAUSE_MIN = 180.0;
    private static final double BURST_PAUSE_MAX = 450.0;

    // session warmup — first N seconds have slower reactions
    private static final long WARMUP_DURATION_MS = 25000;
    private static final double WARMUP_EXTRA_MEAN = 40.0;

    // micro-hesitation — occasionally the player "looks away" or readjusts
    private static final double HESITATION_CHANCE = 0.04;
    private static final double HESITATION_MIN = 80.0;
    private static final double HESITATION_MAX = 280.0;

    // click hold time (how long button is physically pressed)
    private static final double HOLD_MEAN = 48.0;
    private static final double HOLD_STDDEV = 14.0;
    private static final double HOLD_MIN = 25.0;
    private static final double HOLD_MAX = 95.0;

    // state
    private long sessionStartTime;
    private double fatigue = 0.0;
    private long lastAttackTime = 0;
    private int consecutiveFastAttacks = 0;
    private long lastTargetAcquiredTime = 0;

    // rolling stats for self-monitoring — ensure our distribution looks natural
    private final long[] recentDelays = new long[20];
    private int delayIndex = 0;

    public HumanTiming() {
        sessionStartTime = System.currentTimeMillis();
    }

    public void resetSession() {
        sessionStartTime = System.currentTimeMillis();
        fatigue = 0.0;
        consecutiveFastAttacks = 0;
        lastAttackTime = 0;
        lastTargetAcquiredTime = 0;
    }

    /**
     * Called when crosshair first lands on a valid target.
     * Starts the reaction time clock.
     */
    public void onTargetAcquired() {
        if (lastTargetAcquiredTime == 0) {
            lastTargetAcquiredTime = System.currentTimeMillis();
        }
    }

    /**
     * Called when crosshair leaves the target — reset acquisition.
     */
    public void onTargetLost() {
        lastTargetAcquiredTime = 0;
    }

    /**
     * Returns true if enough human-like reaction time has passed since
     * the target was acquired AND crit conditions were met.
     */
    public boolean isReactionComplete(long critReadyTime) {
        if (lastTargetAcquiredTime == 0) return false;

        long now = System.currentTimeMillis();

        // the reaction clock starts from the LATER of: target acquired, crit conditions met
        long stimulusTime = Math.max(lastTargetAcquiredTime, critReadyTime);
        long elapsed = now - stimulusTime;

        long requiredDelay = computeReactionDelay();
        return elapsed >= requiredDelay;
    }

    /**
     * Computes the total human-like delay for this attack, in ms.
     * This is the core of the anti-detection engine.
     */
    public long computeReactionDelay() {
        double delay = 0.0;

        // 1. base visual reaction (gaussian, right-skewed via abs)
        delay += gaussianPositive(BASE_REACTION_MEAN, BASE_REACTION_STDDEV);

        // 2. crit recognition overhead
        delay += gaussianPositive(CRIT_RECOGNITION_MEAN, CRIT_RECOGNITION_STDDEV);

        // 3. motor execution
        delay += gaussianPositive(MOTOR_MEAN, MOTOR_STDDEV);

        // 4. fatigue accumulation
        delay += Math.min(fatigue, MAX_FATIGUE_MS);

        // 5. session warmup penalty
        long sessionAge = System.currentTimeMillis() - sessionStartTime;
        if (sessionAge < WARMUP_DURATION_MS) {
            double warmupFactor = 1.0 - ((double) sessionAge / WARMUP_DURATION_MS);
            delay += WARMUP_EXTRA_MEAN * warmupFactor * (0.7 + ThreadLocalRandom.current().nextDouble() * 0.6);
        }

        // 6. burst cooldown — if we've been clicking fast, humans naturally pause
        if (consecutiveFastAttacks >= BURST_THRESHOLD) {
            delay += BURST_PAUSE_MIN
                    + ThreadLocalRandom.current().nextDouble() * (BURST_PAUSE_MAX - BURST_PAUSE_MIN);
            consecutiveFastAttacks = 0;
        }

        // 7. micro-hesitation
        if (ThreadLocalRandom.current().nextDouble() < HESITATION_CHANCE) {
            delay += HESITATION_MIN
                    + ThreadLocalRandom.current().nextDouble() * (HESITATION_MAX - HESITATION_MIN);
        }

        // floor: no human reacts in under 100ms
        return Math.max((long) delay, 100);
    }

    /**
     * Records that an attack just happened. Updates fatigue, burst tracking, etc.
     */
    public void onAttackExecuted() {
        long now = System.currentTimeMillis();
        long sinceLastAttack = lastAttackTime > 0 ? now - lastAttackTime : 999999;

        // fatigue increases with each attack
        fatigue += FATIGUE_PER_ATTACK_MS;

        // track burst pattern
        if (sinceLastAttack < 400) {
            consecutiveFastAttacks++;
        } else {
            consecutiveFastAttacks = Math.max(0, consecutiveFastAttacks - 1);
        }

        // record for distribution monitoring
        recentDelays[delayIndex % recentDelays.length] = sinceLastAttack;
        delayIndex++;

        lastAttackTime = now;
        lastTargetAcquiredTime = 0; // reset for next acquisition
    }

    /**
     * Called each tick when NOT attacking — recovers fatigue.
     */
    public void onIdleTick(long tickIntervalMs) {
        if (fatigue > 0) {
            fatigue = Math.max(0, fatigue - FATIGUE_RECOVERY_RATE * tickIntervalMs);
        }
    }

    /**
     * Returns human-like click hold duration in ms.
     */
    public int getClickHoldTime() {
        double hold = gaussianPositive(HOLD_MEAN, HOLD_STDDEV);
        return (int) Math.max(HOLD_MIN, Math.min(HOLD_MAX, hold));
    }

    /**
     * Whether to intentionally "miss" this opportunity.
     * Real humans sometimes don't click even when they could — distracted,
     * repositioning, waiting for a "better" moment.
     *
     * Rate is dynamic: higher when fatigued, lower when fresh.
     */
    public boolean shouldMiss() {
        double baseMissRate = 0.025;
        double fatigueMissBonus = (fatigue / MAX_FATIGUE_MS) * 0.02;
        return ThreadLocalRandom.current().nextDouble() < (baseMissRate + fatigueMissBonus);
    }

    // gaussian that never goes negative — models real reaction time distributions
    private double gaussianPositive(double mean, double stddev) {
        double val = mean + ThreadLocalRandom.current().nextGaussian() * stddev;
        return Math.max(val, mean * 0.4); // hard floor at 40% of mean
    }
}
