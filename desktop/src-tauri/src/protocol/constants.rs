//! Protocol constants. Must match `shared/protocol/constants.json`
//! (checked by the test below).

pub const PROTOCOL_VERSION: u32 = 1;
pub const SUPPORTED_PROTOCOL_MIN: u32 = 1;
pub const SUPPORTED_PROTOCOL_MAX: u32 = 1;

pub const URI_SCHEME: &str = "openscreen";
pub const URI_HOST: &str = "pair";
pub const MAX_HOSTS: usize = 4;
pub const MAX_NAME_LENGTH: usize = 32;

pub const WEBSOCKET_PATH: &str = "/v1/signal";
pub const WEBSOCKET_SUBPROTOCOL: &str = "openscreen.v1";
pub const DEFAULT_PORT: u16 = 47820;
pub const FALLBACK_PORTS: std::ops::RangeInclusive<u16> = 47821..=47829;

pub const MAX_MESSAGE_BYTES: usize = 65_536;
pub const MAX_SDP_CHARS: usize = 32_768;
pub const MAX_CANDIDATE_CHARS: usize = 1_024;
pub const MAX_MESSAGES_PER_SECOND: u32 = 50;
pub const MAX_AUTH_FAILURES_PER_IP_PER_MINUTE: u32 = 5;
pub const MAX_RESUME_REJECTIONS: u32 = 3;

pub const PAIRING_TOKEN_TTL_MS: u64 = 300_000;
pub const EXPIRED_TOKEN_MEMORY_MS: u64 = 600_000;
pub const HELLO_TIMEOUT_MS: u64 = 10_000;
pub const RESUME_WINDOW_MS: u64 = 60_000;
pub const PING_INTERVAL_MS: u64 = 5_000;
pub const PEER_DEAD_AFTER_MS: u64 = 15_000;

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::shared_fixtures;

    #[test]
    fn constants_match_shared_definition() {
        let c = shared_fixtures::json("constants.json");
        let int = |v: &serde_json::Value| v.as_u64().expect("integer");

        assert_eq!(int(&c["protocolVersion"]), u64::from(PROTOCOL_VERSION));
        assert_eq!(
            int(&c["supportedProtocol"]["min"]),
            u64::from(SUPPORTED_PROTOCOL_MIN)
        );
        assert_eq!(
            int(&c["supportedProtocol"]["max"]),
            u64::from(SUPPORTED_PROTOCOL_MAX)
        );

        assert_eq!(c["pairingUri"]["scheme"], URI_SCHEME);
        assert_eq!(c["pairingUri"]["host"], URI_HOST);
        assert_eq!(int(&c["pairingUri"]["maxHosts"]), MAX_HOSTS as u64);
        assert_eq!(
            int(&c["pairingUri"]["maxNameLength"]),
            MAX_NAME_LENGTH as u64
        );

        assert_eq!(c["websocket"]["path"], WEBSOCKET_PATH);
        assert_eq!(c["websocket"]["subprotocol"], WEBSOCKET_SUBPROTOCOL);

        assert_eq!(int(&c["ports"]["default"]), u64::from(DEFAULT_PORT));
        assert_eq!(
            int(&c["ports"]["fallbackFirst"]),
            u64::from(*FALLBACK_PORTS.start())
        );
        assert_eq!(
            int(&c["ports"]["fallbackLast"]),
            u64::from(*FALLBACK_PORTS.end())
        );

        let l = &c["limits"];
        assert_eq!(int(&l["maxMessageBytes"]), MAX_MESSAGE_BYTES as u64);
        assert_eq!(int(&l["maxSdpChars"]), MAX_SDP_CHARS as u64);
        assert_eq!(int(&l["maxCandidateChars"]), MAX_CANDIDATE_CHARS as u64);
        assert_eq!(
            int(&l["maxMessagesPerSecond"]),
            u64::from(MAX_MESSAGES_PER_SECOND)
        );
        assert_eq!(
            int(&l["maxAuthFailuresPerIpPerMinute"]),
            u64::from(MAX_AUTH_FAILURES_PER_IP_PER_MINUTE)
        );
        assert_eq!(
            int(&l["maxResumeRejections"]),
            u64::from(MAX_RESUME_REJECTIONS)
        );

        let t = &c["timeoutsMs"];
        assert_eq!(int(&t["pairingTokenTtl"]), PAIRING_TOKEN_TTL_MS);
        assert_eq!(int(&t["expiredTokenMemory"]), EXPIRED_TOKEN_MEMORY_MS);
        assert_eq!(int(&t["helloTimeout"]), HELLO_TIMEOUT_MS);
        assert_eq!(int(&t["resumeWindow"]), RESUME_WINDOW_MS);
        assert_eq!(int(&t["pingInterval"]), PING_INTERVAL_MS);
        assert_eq!(int(&t["peerDeadAfter"]), PEER_DEAD_AFTER_MS);
    }
}
