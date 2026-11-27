//! Pairing tokens and secrets (docs/PROTOCOL.md §5.1).
//!
//! Tokens are 32 bytes from the OS CSPRNG, single use, and expire. Only
//! SHA-256 hashes are kept for comparison, so comparison is constant-time
//! and a memory dump of old state does not reveal usable tokens.

use std::collections::VecDeque;
use std::time::{Duration, Instant};

use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use sha2::{Digest, Sha256};
use subtle::ConstantTimeEq;

use crate::protocol::constants::{EXPIRED_TOKEN_MEMORY_MS, PAIRING_TOKEN_TTL_MS};

/// `n` random bytes, base64url without padding.
pub fn random_b64url(n: usize) -> String {
    let mut buf = vec![0u8; n];
    getrandom::fill(&mut buf).expect("OS random number generator unavailable");
    URL_SAFE_NO_PAD.encode(buf)
}

pub fn new_token() -> String {
    random_b64url(32)
}

pub fn new_session_id() -> String {
    random_b64url(16)
}

pub fn hash(secret: &str) -> [u8; 32] {
    Sha256::digest(secret.as_bytes()).into()
}

/// Constant-time comparison of a presented secret against a stored hash.
pub fn matches(presented: &str, stored: &[u8; 32]) -> bool {
    hash(presented).ct_eq(stored).into()
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TokenRejection {
    Invalid,
    Expired,
}

struct Current {
    value: String,
    hash: [u8; 32],
    issued: Instant,
}

/// The receiver's single live pairing token plus memory of recently expired ones.
pub struct TokenManager {
    ttl: Duration,
    memory: Duration,
    current: Option<Current>,
    expired: VecDeque<([u8; 32], Instant)>,
}

impl Default for TokenManager {
    fn default() -> Self {
        Self::new(
            Duration::from_millis(PAIRING_TOKEN_TTL_MS),
            Duration::from_millis(EXPIRED_TOKEN_MEMORY_MS),
        )
    }
}

impl TokenManager {
    pub fn new(ttl: Duration, memory: Duration) -> Self {
        Self {
            ttl,
            memory,
            current: None,
            expired: VecDeque::new(),
        }
    }

    /// Issues a fresh token, retiring any current one.
    pub fn issue(&mut self, now: Instant) -> String {
        self.retire(now);
        let value = new_token();
        self.current = Some(Current {
            hash: hash(&value),
            value: value.clone(),
            issued: now,
        });
        value
    }

    /// The live token and when it expires, if any.
    pub fn current(&self, now: Instant) -> Option<(&str, Instant)> {
        self.current
            .as_ref()
            .filter(|c| now < c.issued + self.ttl)
            .map(|c| (c.value.as_str(), c.issued + self.ttl))
    }

    /// Withdraws the live token without issuing a new one (e.g. while a session exists).
    pub fn revoke(&mut self, now: Instant) {
        self.retire(now);
    }

    /// Returns true when the current token has expired (caller rotates the QR code).
    pub fn needs_rotation(&self, now: Instant) -> bool {
        self.current
            .as_ref()
            .is_some_and(|c| now >= c.issued + self.ttl)
    }

    /// Validates and **consumes** a presented token (single use, even on success).
    pub fn redeem(&mut self, presented: &str, now: Instant) -> Result<(), TokenRejection> {
        self.prune(now);
        if let Some(c) = &self.current {
            if matches(presented, &c.hash) {
                let fresh = now < c.issued + self.ttl;
                self.retire(now);
                return if fresh {
                    Ok(())
                } else {
                    Err(TokenRejection::Expired)
                };
            }
        }
        let h = hash(presented);
        if self.expired.iter().any(|(e, _)| bool::from(e.ct_eq(&h))) {
            Err(TokenRejection::Expired)
        } else {
            Err(TokenRejection::Invalid)
        }
    }

    fn retire(&mut self, now: Instant) {
        if let Some(c) = self.current.take() {
            // Remember only tokens that actually expired, so a used token reads as "invalid".
            if now >= c.issued + self.ttl {
                self.expired.push_back((c.hash, now));
            }
        }
        self.prune(now);
    }

    fn prune(&mut self, now: Instant) {
        while self
            .expired
            .front()
            .is_some_and(|(_, t)| now.duration_since(*t) > self.memory)
        {
            self.expired.pop_front();
        }
        while self.expired.len() > 32 {
            self.expired.pop_front();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn mgr() -> TokenManager {
        TokenManager::new(Duration::from_secs(300), Duration::from_secs(600))
    }

    #[test]
    fn tokens_are_32_random_bytes() {
        let a = new_token();
        let b = new_token();
        assert_eq!(a.len(), 43);
        assert_ne!(a, b);
        assert_eq!(URL_SAFE_NO_PAD.decode(&a).unwrap().len(), 32);
        assert_eq!(new_session_id().len(), 22);
    }

    #[test]
    fn token_is_single_use() {
        let t0 = Instant::now();
        let mut m = mgr();
        let token = m.issue(t0);
        assert_eq!(m.redeem(&token, t0), Ok(()));
        assert_eq!(m.redeem(&token, t0), Err(TokenRejection::Invalid));
        assert!(m.current(t0).is_none());
    }

    #[test]
    fn wrong_token_is_invalid_and_does_not_consume_the_real_one() {
        let t0 = Instant::now();
        let mut m = mgr();
        let token = m.issue(t0);
        assert_eq!(m.redeem(&new_token(), t0), Err(TokenRejection::Invalid));
        assert_eq!(m.redeem(&token, t0), Ok(()));
    }

    #[test]
    fn expired_tokens_are_reported_as_expired_for_a_while() {
        let t0 = Instant::now();
        let mut m = mgr();
        let old = m.issue(t0);
        let t1 = t0 + Duration::from_secs(301);
        assert!(m.needs_rotation(t1));
        assert!(m.current(t1).is_none());
        let new = m.issue(t1);
        assert_eq!(m.redeem(&old, t1), Err(TokenRejection::Expired));
        assert_eq!(
            m.redeem(&old, t1 + Duration::from_secs(601)),
            Err(TokenRejection::Invalid)
        );
        assert_eq!(m.current(t1).map(|(v, _)| v.to_owned()), Some(new));
    }

    #[test]
    fn presenting_the_current_token_after_expiry_is_expired() {
        let t0 = Instant::now();
        let mut m = mgr();
        let token = m.issue(t0);
        assert_eq!(
            m.redeem(&token, t0 + Duration::from_secs(300)),
            Err(TokenRejection::Expired)
        );
    }

    #[test]
    fn constant_time_match() {
        let s = new_token();
        assert!(matches(&s, &hash(&s)));
        assert!(!matches(&new_token(), &hash(&s)));
    }
}
