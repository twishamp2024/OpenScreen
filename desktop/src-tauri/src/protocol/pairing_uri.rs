//! Pairing URI (QR code contents), docs/PROTOCOL.md §2.
//!
//! The receiver *builds* these URIs; parsing exists so the builder can be
//! round-trip tested and so both apps are held to the same shared vectors
//! (`shared/protocol/fixtures/pairing-uris.json`).

use std::fmt;
use std::net::Ipv4Addr;

use percent_encoding::{percent_decode_str, utf8_percent_encode, AsciiSet, NON_ALPHANUMERIC};

use super::constants::{MAX_HOSTS, MAX_NAME_LENGTH, PROTOCOL_VERSION, URI_HOST, URI_SCHEME};

/// Characters percent-encoded in parameter values: everything except RFC 3986 "unreserved".
const VALUE_ENCODE_SET: &AsciiSet = &NON_ALPHANUMERIC
    .remove(b'-')
    .remove(b'.')
    .remove(b'_')
    .remove(b'~');

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PairingInfo {
    pub version: u32,
    /// Private IPv4 addresses, in preference order.
    pub hosts: Vec<Ipv4Addr>,
    pub port: u16,
    /// 32-byte pairing token, base64url without padding.
    pub token: String,
    /// SHA-256 of the receiver certificate (DER), base64url without padding.
    pub fingerprint: String,
    pub name: Option<String>,
}

/// Rejection reasons, in check order (must match `pairingUriErrors` in constants.json).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PairingUriError {
    NotAPairingUri,
    DuplicateParam,
    MissingParam,
    BadVersion,
    UnsupportedVersion,
    BadHost,
    BadPort,
    BadToken,
    BadFingerprint,
    BadName,
}

impl PairingUriError {
    pub const ALL: [PairingUriError; 10] = [
        Self::NotAPairingUri,
        Self::DuplicateParam,
        Self::MissingParam,
        Self::BadVersion,
        Self::UnsupportedVersion,
        Self::BadHost,
        Self::BadPort,
        Self::BadToken,
        Self::BadFingerprint,
        Self::BadName,
    ];

    /// The wire/fixture spelling, e.g. `BAD_HOST`.
    pub fn code(self) -> &'static str {
        match self {
            Self::NotAPairingUri => "NOT_A_PAIRING_URI",
            Self::DuplicateParam => "DUPLICATE_PARAM",
            Self::MissingParam => "MISSING_PARAM",
            Self::BadVersion => "BAD_VERSION",
            Self::UnsupportedVersion => "UNSUPPORTED_VERSION",
            Self::BadHost => "BAD_HOST",
            Self::BadPort => "BAD_PORT",
            Self::BadToken => "BAD_TOKEN",
            Self::BadFingerprint => "BAD_FINGERPRINT",
            Self::BadName => "BAD_NAME",
        }
    }
}

impl fmt::Display for PairingUriError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.code())
    }
}

impl std::error::Error for PairingUriError {}

/// RFC 1918 only: 10/8, 172.16/12, 192.168/16.
pub fn is_private_ipv4(ip: Ipv4Addr) -> bool {
    let [a, b, ..] = ip.octets();
    a == 10 || (a == 172 && (16..=31).contains(&b)) || (a == 192 && b == 168)
}

fn is_b64url_32(s: &str) -> bool {
    s.len() == 43
        && s.bytes()
            .all(|c| c.is_ascii_alphanumeric() || c == b'-' || c == b'_')
}

/// Plain decimal, no sign or leading zeros, at most 9 digits.
fn parse_decimal(s: &str) -> Option<u32> {
    let ok = !s.is_empty()
        && s.len() <= 9
        && s.bytes().all(|c| c.is_ascii_digit())
        && !s.starts_with('0');
    if ok {
        s.parse().ok()
    } else {
        None
    }
}

fn parse_ipv4(s: &str) -> Option<Ipv4Addr> {
    let parts: Vec<&str> = s.split('.').collect();
    if parts.len() != 4 {
        return None;
    }
    let mut octets = [0u8; 4];
    for (i, part) in parts.iter().enumerate() {
        let valid = !part.is_empty()
            && part.len() <= 3
            && part.bytes().all(|c| c.is_ascii_digit())
            && (part.len() == 1 || !part.starts_with('0'));
        if !valid {
            return None;
        }
        octets[i] = part.parse::<u16>().ok().filter(|&v| v <= 255)? as u8;
    }
    Some(Ipv4Addr::from(octets))
}

/// Strict percent-decoding to UTF-8 (`+` stays `+`); `None` if malformed.
fn percent_decode_strict(raw: &str) -> Option<String> {
    let bytes = raw.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' {
            let hex = bytes.get(i + 1..i + 3)?;
            if !hex.iter().all(u8::is_ascii_hexdigit) {
                return None;
            }
            i += 3;
        } else {
            i += 1;
        }
    }
    percent_decode_str(raw)
        .decode_utf8()
        .ok()
        .map(|s| s.into_owned())
}

fn valid_name(name: &str) -> bool {
    let len = name.chars().count();
    (1..=MAX_NAME_LENGTH).contains(&len) && !name.chars().any(|c| c <= '\u{1F}' || c == '\u{7F}')
}

impl PairingInfo {
    /// Builds the URI shown in the QR code.
    pub fn to_uri(&self) -> String {
        let hosts: Vec<String> = self.hosts.iter().map(Ipv4Addr::to_string).collect();
        let mut uri = format!(
            "{URI_SCHEME}://{URI_HOST}?v={}&h={}&p={}&t={}&f={}",
            self.version,
            hosts.join(","),
            self.port,
            self.token,
            self.fingerprint
        );
        if let Some(name) = &self.name {
            uri.push_str("&n=");
            uri.extend(utf8_percent_encode(name, VALUE_ENCODE_SET));
        }
        uri
    }

    pub fn parse(uri: &str) -> Result<Self, PairingUriError> {
        Self::parse_with_version(uri, PROTOCOL_VERSION)
    }

    pub fn parse_with_version(uri: &str, supported_version: u32) -> Result<Self, PairingUriError> {
        use PairingUriError::*;

        let (scheme, rest) = uri.split_once("://").ok_or(NotAPairingUri)?;
        if !scheme.eq_ignore_ascii_case(URI_SCHEME) {
            return Err(NotAPairingUri);
        }
        let (host, query) = rest.split_once('?').unwrap_or((rest, ""));
        if !host.eq_ignore_ascii_case(URI_HOST) {
            return Err(NotAPairingUri);
        }

        // Index order: v h p t f n. Inner `None` = present but not decodable.
        const KEYS: [&str; 6] = ["v", "h", "p", "t", "f", "n"];
        let mut values: [Option<Option<String>>; 6] = Default::default();
        for segment in query.split('&').filter(|s| !s.is_empty()) {
            let (key, raw) = segment.split_once('=').unwrap_or((segment, ""));
            let Some(index) = KEYS.iter().position(|k| *k == key) else {
                continue;
            };
            if values[index].is_some() {
                return Err(DuplicateParam);
            }
            values[index] = Some(percent_decode_strict(raw));
        }
        if values[..5].iter().any(Option::is_none) {
            return Err(MissingParam);
        }
        let [v, h, p, t, f, n] = values;
        let (v, h, p, t, f) = (
            v.flatten(),
            h.flatten(),
            p.flatten(),
            t.flatten(),
            f.flatten(),
        );

        let version = v.as_deref().and_then(parse_decimal).ok_or(BadVersion)?;
        if version > supported_version {
            return Err(UnsupportedVersion);
        }

        let h = h.ok_or(BadHost)?;
        let entries: Vec<&str> = h.split(',').collect();
        if entries.len() > MAX_HOSTS {
            return Err(BadHost);
        }
        let hosts = entries
            .iter()
            .map(|e| {
                parse_ipv4(e)
                    .filter(|ip| is_private_ipv4(*ip))
                    .ok_or(BadHost)
            })
            .collect::<Result<Vec<_>, _>>()?;

        let port = p
            .as_deref()
            .and_then(parse_decimal)
            .and_then(|p| u16::try_from(p).ok())
            .ok_or(BadPort)?;

        let token = t.filter(|t| is_b64url_32(t)).ok_or(BadToken)?;
        let fingerprint = f.filter(|f| is_b64url_32(f)).ok_or(BadFingerprint)?;

        let name = match n {
            None => None,
            Some(decoded) => Some(decoded.filter(|n| valid_name(n)).ok_or(BadName)?),
        };

        Ok(PairingInfo {
            version,
            hosts,
            port,
            token,
            fingerprint,
            name,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::shared_fixtures;
    use serde_json::Value;

    fn vectors() -> Value {
        shared_fixtures::json("fixtures/pairing-uris.json")
    }

    fn supported(v: &Value) -> u32 {
        v["supportedVersion"].as_u64().unwrap() as u32
    }

    fn expected_info(e: &Value) -> PairingInfo {
        PairingInfo {
            version: e["version"].as_u64().unwrap() as u32,
            hosts: e["hosts"]
                .as_array()
                .unwrap()
                .iter()
                .map(|h| h.as_str().unwrap().parse().unwrap())
                .collect(),
            port: e["port"].as_u64().unwrap() as u16,
            token: e["token"].as_str().unwrap().to_owned(),
            fingerprint: e["fingerprint"].as_str().unwrap().to_owned(),
            name: e["name"].as_str().map(str::to_owned),
        }
    }

    #[test]
    fn accepts_every_valid_vector() {
        let v = vectors();
        let cases = v["valid"].as_array().unwrap();
        assert!(!cases.is_empty());
        for case in cases {
            let name = case["name"].as_str().unwrap();
            let parsed =
                PairingInfo::parse_with_version(case["uri"].as_str().unwrap(), supported(&v))
                    .unwrap_or_else(|e| panic!("case {name:?} rejected with {e}"));
            assert_eq!(parsed, expected_info(&case["expected"]), "case {name:?}");
        }
    }

    #[test]
    fn rejects_every_invalid_vector_with_expected_code() {
        let v = vectors();
        let cases = v["invalid"].as_array().unwrap();
        assert!(!cases.is_empty());
        for case in cases {
            let name = case["name"].as_str().unwrap();
            let result =
                PairingInfo::parse_with_version(case["uri"].as_str().unwrap(), supported(&v));
            match result {
                Ok(info) => panic!("case {name:?} was accepted: {info:?}"),
                Err(e) => assert_eq!(e.code(), case["error"].as_str().unwrap(), "case {name:?}"),
            }
        }
    }

    #[test]
    fn built_uris_round_trip() {
        let v = vectors();
        for case in v["valid"].as_array().unwrap() {
            let info = expected_info(&case["expected"]);
            let uri = info.to_uri();
            assert_eq!(PairingInfo::parse(&uri), Ok(info), "round trip of {uri}");
        }
    }

    #[test]
    fn name_with_reserved_characters_round_trips() {
        let info = PairingInfo {
            version: 1,
            hosts: vec![Ipv4Addr::new(192, 168, 1, 20)],
            port: 47820,
            token: "lXPoAB1-XtnXbHZwLlJ13iMUH7T1NeqCWmA9FvHqSe8".into(),
            fingerprint: "Pj4_A0Jni6caoMTewX711vX8StLStu7E1ZA-89W1rz8".into(),
            name: Some("R&D + Lab = 100%".into()),
        };
        assert_eq!(PairingInfo::parse(&info.to_uri()), Ok(info));
    }

    #[test]
    fn error_codes_match_shared_list_and_order() {
        let c = shared_fixtures::json("constants.json");
        let shared: Vec<&str> = c["pairingUriErrors"]
            .as_array()
            .unwrap()
            .iter()
            .map(|e| e.as_str().unwrap())
            .collect();
        let ours: Vec<&str> = PairingUriError::ALL.iter().map(|e| e.code()).collect();
        assert_eq!(shared, ours);
    }

    #[test]
    fn malformed_percent_encoding_is_rejected() {
        let uri = "openscreen://pair?v=1&h=192.168.1.20&p=47820\
                   &t=lXPoAB1-XtnXbHZwLlJ13iMUH7T1NeqCWmA9FvHqSe8\
                   &f=Pj4_A0Jni6caoMTewX711vX8StLStu7E1ZA-89W1rz8&n=%zz";
        assert_eq!(PairingInfo::parse(uri), Err(PairingUriError::BadName));
    }
}
