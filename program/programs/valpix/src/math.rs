//! Pure arithmetic for income and the fee pool. No Solana types, so every
//! rule here is unit-tested on the host.
//!
//! Rounding always favours the pool: CLPC charged rounds up, CLPC paid out
//! rounds down.

use crate::constants::SECONDS_PER_DAY;

/// Income earned between two chain timestamps, in micro-CLPC, rounded down.
/// Zero when no time has passed or the clock appears to run backwards.
pub fn accrued(daily_rate: u64, from_ts: i64, to_ts: i64) -> u64 {
    if to_ts <= from_ts {
        return 0;
    }
    let elapsed = (to_ts - from_ts) as u128;
    let earned = elapsed * daily_rate as u128 / SECONDS_PER_DAY as u128;
    u64::try_from(earned).unwrap_or(u64::MAX)
}

/// CLPC a member must give the pool to take `lamports_out` SOL from it,
/// rounded up. `None` when the pool cannot supply that much.
pub fn quote_clpc_in(sol_reserve: u64, clpc_reserve: u64, lamports_out: u64) -> Option<u64> {
    if lamports_out >= sol_reserve {
        return None;
    }
    let numerator = clpc_reserve as u128 * lamports_out as u128;
    let denominator = (sol_reserve - lamports_out) as u128;
    u64::try_from(numerator.div_ceil(denominator)).ok()
}

/// CLPC the pool gives for `lamports_in` SOL added to it, rounded down.
pub fn quote_clpc_out(sol_reserve: u64, clpc_reserve: u64, lamports_in: u64) -> u64 {
    let denominator = sol_reserve as u128 + lamports_in as u128;
    if denominator == 0 {
        return 0;
    }
    let out = clpc_reserve as u128 * lamports_in as u128 / denominator;
    // `out` is at most `clpc_reserve`, so it always fits.
    out as u64
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::constants::UNIT;

    #[test]
    fn accrued_one_day_is_1000_clpc() {
        assert_eq!(accrued(1000 * UNIT, 0, 86_400), 1000 * UNIT);
    }

    #[test]
    fn accrued_one_second_floors() {
        assert_eq!(accrued(1000 * UNIT, 0, 1), 11_574);
    }

    #[test]
    fn accrued_is_zero_for_zero_elapsed() {
        assert_eq!(accrued(1000 * UNIT, 50, 50), 0);
        assert_eq!(accrued(1000 * UNIT, 50, 40), 0);
    }

    #[test]
    fn accrued_does_not_overflow_over_a_century() {
        assert_eq!(
            accrued(1000 * UNIT, 0, 100 * 365 * 86_400),
            36_500_000 * UNIT
        );
    }

    #[test]
    fn fee_is_about_one_clpc_at_the_starting_pool() {
        assert_eq!(
            quote_clpc_in(1_000_000_000, 200_000 * UNIT, 5_000),
            Some(1_000_006)
        );
    }

    #[test]
    fn fee_is_about_four_clpc_at_half_the_sol() {
        assert_eq!(
            quote_clpc_in(500_000_000, 400_000 * UNIT, 5_000),
            Some(4_000_041)
        );
    }

    #[test]
    fn quote_in_is_none_when_asking_for_the_whole_reserve() {
        assert_eq!(quote_clpc_in(5_000, 1, 5_000), None);
        assert_eq!(quote_clpc_in(0, 1, 1), None);
    }

    #[test]
    fn refill_restores_the_starting_price() {
        assert_eq!(
            quote_clpc_out(500_000_000, 400_000 * UNIT, 500_000_000),
            200_000 * UNIT
        );
    }

    #[test]
    fn rounding_never_shrinks_k() {
        let (s, c) = (1_000_000_000u64, 200_000 * UNIT);
        let fee = quote_clpc_in(s, c, 5_000).unwrap();
        assert!((s as u128 - 5_000) * (c as u128 + fee as u128) >= s as u128 * c as u128);
        let out = quote_clpc_out(s, c, 7_777);
        assert!((s as u128 + 7_777) * (c as u128 - out as u128) >= s as u128 * c as u128);
    }
}
