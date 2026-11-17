//! Reconnect scheduling: bounded exponential backoff with jitter, as a pure
//! function so the whole schedule is unit-testable without a network.

use std::time::Duration;
use std::time::{SystemTime, UNIX_EPOCH};

/// Delay before the first retry.
pub const BASE_DELAY: Duration = Duration::from_millis(500);
/// Hard ceiling for any single retry delay.
pub const MAX_DELAY: Duration = Duration::from_secs(16);
/// Added delay is at most this fraction of the uncapped delay: the jittered
/// delay always lies in `[raw, raw * (1 + JITTER_FRACTION)]`.
pub const JITTER_FRACTION: f64 = 0.5;

/// Bounded reconnect policy. Every field is a deliberate constant of the
/// product, not a magic number at a call site.
#[derive(Debug, Clone)]
pub struct ReconnectPolicy {
    pub base_delay: Duration,
    pub max_delay: Duration,
    /// Retry budget: after this many consecutive failed attempts, reconnecting
    /// gives up and the caller decides.
    pub max_attempts: u32,
    pub jitter_fraction: f64,
}

impl Default for ReconnectPolicy {
    fn default() -> Self {
        Self {
            base_delay: BASE_DELAY,
            max_delay: MAX_DELAY,
            max_attempts: 8,
            jitter_fraction: JITTER_FRACTION,
        }
    }
}

impl ReconnectPolicy {
    /// The delay to wait before retry #`failed_attempts + 1`, after
    /// `failed_attempts` consecutive failures (so `failed_attempts >= 1`).
    ///
    /// Pure: `jitter01` is the injected randomness in `[0, 1)` (values outside
    /// the range are clamped), and the same inputs always produce the same
    /// delay. Properties, in this order:
    ///
    /// * **base** — `delay(_, 0.0) == base_delay` at the first retry;
    /// * **monotonic** — for a fixed jitter the delays never decrease as
    ///   attempts climb;
    /// * **capped** — no delay ever exceeds `max_delay`;
    /// * **jittered within bounds** — the delay of a retry lies in
    ///   `[raw, raw * (1 + jitter_fraction)]` for that retry's uncapped `raw`,
    ///   and jitter never pushes past `max_delay`.
    pub fn delay(&self, failed_attempts: u32, jitter01: f64) -> Duration {
        let raw = uncapped(self, failed_attempts).as_secs_f64();
        let jitter = jitter01.clamp(0.0, 1.0);
        let factor = 1.0 + self.jitter_fraction * jitter;
        let delay = (raw * factor).max(0.0);
        Duration::from_secs_f64(delay.min(self.max_delay.as_secs_f64()))
    }

    /// True when the retry budget is spent after `failed_attempts` consecutive
    /// failures.
    pub fn exhausted(&self, failed_attempts: u32) -> bool {
        failed_attempts >= self.max_attempts
    }
}

/// The uncapped delay after `failed_attempts` failures: base doubled each
/// retry.
fn uncapped(policy: &ReconnectPolicy, failed_attempts: u32) -> Duration {
    let exponent = failed_attempts.saturating_sub(1);
    let clamped = exponent.min(u32::from(u16::MAX)); // shift is bounded; the min() below caps the value anyway
    policy.base_delay.saturating_mul(2u32.saturating_pow(clamped))
}

/// A tiny SplitMix64 pseudo-random source for jitter. Deterministic under a
/// fixed seed, so schedules can be replayed in tests; tests inject jitter by
/// number instead where they want exact control.
#[derive(Debug, Clone)]
pub struct Jitter {
    state: u64,
}

impl Jitter {
    pub fn from_seed(seed: u64) -> Self {
        Self { state: seed }
    }

    /// Seeded from wall-clock nanoseconds mixed with a process-unique constant,
    /// for callers that do not care about reproducibility.
    pub fn from_entropy() -> Self {
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|since| since.as_nanos() as u64)
            .unwrap_or(0x9E3779B97F4A7C15);
        Self::from_seed(nanos ^ 0x9E3779B97F4A7C15)
    }

    /// Sample the next jitter in `[0, 1)`. Named to avoid the
    /// `Iterator::next` confusion.
    pub fn sample(&mut self) -> f64 {
        self.state = self.state.wrapping_add(0x9E37_79B9_7F4A_7C15);
        let mut z = self.state;
        z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
        z ^= z >> 31;
        (z >> 11) as f64 / (1u64 << 53) as f64
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn policy() -> ReconnectPolicy {
        ReconnectPolicy::default()
    }

    fn seconds(duration: Duration) -> f64 {
        duration.as_secs_f64()
    }

    #[test]
    fn first_retry_waits_the_base_delay_when_unjittered() {
        assert_eq!(policy().delay(1, 0.0), BASE_DELAY);
    }

    #[test]
    fn schedule_is_monotonic_for_fixed_jitter() {
        for jitter in [0.0, 0.25, 0.999_999] {
            let mut previous = Duration::ZERO;
            for attempts in 1..=12 {
                let delay = policy().delay(attempts, jitter);
                assert!(delay >= previous, "{attempts}: {delay:?} < {previous:?}");
                previous = delay;
            }
        }
    }

    #[test]
    fn delays_are_capped() {
        assert_eq!(policy().delay(30, 0.0), MAX_DELAY);
        assert_eq!(policy().delay(30, 1.0), MAX_DELAY);
        for attempts in 1..=30 {
            assert!(policy().delay(attempts, 1.0) <= MAX_DELAY);
        }
    }

    #[test]
    fn growth_doubles_until_the_cap() {
        let policy = policy();
        assert_eq!(policy.delay(1, 0.0), Duration::from_millis(500));
        assert_eq!(policy.delay(2, 0.0), Duration::from_millis(1000));
        assert_eq!(policy.delay(3, 0.0), Duration::from_millis(2000));
        assert_eq!(policy.delay(4, 0.0), Duration::from_millis(4000));
        assert_eq!(policy.delay(5, 0.0), Duration::from_millis(8000));
        assert_eq!(policy.delay(6, 0.0), Duration::from_millis(16_000));
        assert_eq!(policy.delay(7, 0.0), MAX_DELAY);
    }

    #[test]
    fn jitter_stays_within_bounds() {
        let policy = policy();
        let jitters = [0.0, 0.1, 0.5, 0.90, 0.999_999];
        for attempts in 1..=10 {
            let raw = seconds(uncapped(&policy, attempts)).min(seconds(MAX_DELAY));
            for jitter in jitters {
                let delay = seconds(policy.delay(attempts, jitter));
                assert!(
                    delay >= raw - f64::EPSILON && delay <= raw * (1.0 + JITTER_FRACTION) + f64::EPSILON,
                    "attempts {attempts}, jitter {jitter}: {delay} outside [{raw}, {}]",
                    raw * (1.0 + JITTER_FRACTION)
                );
                assert!(delay <= seconds(MAX_DELAY) + f64::EPSILON);
            }
        }
    }

    #[test]
    fn out_of_range_jitter_is_clamped() {
        let policy = policy();
        assert_eq!(policy.delay(3, -5.0), policy.delay(3, 0.0));
        assert_eq!(policy.delay(3, 42.0), policy.delay(3, 1.0));
    }

    #[test]
    fn retry_budget_boundaries() {
        let policy = policy();
        assert!(!policy.exhausted(0));
        assert!(!policy.exhausted(policy.max_attempts - 1));
        assert!(policy.exhausted(policy.max_attempts));
        assert!(policy.exhausted(policy.max_attempts + 1));
    }

    #[test]
    fn jitter_source_is_deterministic_under_a_seed() {
        let mut a = Jitter::from_seed(42);
        let mut b = Jitter::from_seed(42);
        for _ in 0..8 {
            assert_eq!(a.sample(), b.sample());
        }
    }

    #[test]
    fn jitter_values_lie_in_unit_interval() {
        let mut jitter = Jitter::from_seed(7);
        for _ in 0..1000 {
            let value = jitter.sample();
            assert!((0.0..1.0).contains(&value), "{value}");
        }
    }
}
