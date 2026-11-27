//! Local network interface discovery (docs/ARCHITECTURE.md §8).
//!
//! Only private (RFC 1918) IPv4 addresses on physical-looking adapters are
//! offered, Wi-Fi first. The signaling server binds to exactly these
//! addresses and never to 0.0.0.0.

use std::net::{IpAddr, Ipv4Addr};

use serde::Serialize;

use crate::protocol::constants::MAX_HOSTS;
use crate::protocol::pairing_uri::is_private_ipv4;

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LocalInterface {
    pub name: String,
    pub ip: Ipv4Addr,
    pub prefix_len: u8,
    pub kind: InterfaceKind,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum InterfaceKind {
    Wifi,
    Ethernet,
    Other,
}

/// Adapter-name fragments of virtual, tunnel and VPN adapters we never advertise.
const VIRTUAL_HINTS: [&str; 16] = [
    "vethernet",
    "virtualbox",
    "vmware",
    "hyper-v",
    "wsl",
    "docker",
    "loopback",
    "tap",
    "tun",
    "tailscale",
    "zerotier",
    "wireguard",
    "bluetooth",
    "vpn",
    "npcap",
    "pseudo",
];

pub fn classify(name: &str) -> Option<InterfaceKind> {
    let n = name.to_ascii_lowercase();
    if VIRTUAL_HINTS.iter().any(|h| n.contains(h)) {
        return None;
    }
    if n.contains("wi-fi") || n.contains("wifi") || n.contains("wlan") || n.contains("wireless") {
        Some(InterfaceKind::Wifi)
    } else if n.contains("ethernet") || n.starts_with("eth") || n.starts_with("en") {
        Some(InterfaceKind::Ethernet)
    } else {
        Some(InterfaceKind::Other)
    }
}

/// Pure selection logic (unit tested): filter, de-duplicate, rank, cap at [`MAX_HOSTS`].
pub fn select(raw: impl IntoIterator<Item = (String, Ipv4Addr, u8)>) -> Vec<LocalInterface> {
    let mut out: Vec<LocalInterface> = raw
        .into_iter()
        .filter(|(_, ip, _)| is_private_ipv4(*ip) && !ip.is_loopback() && !ip.is_link_local())
        .filter_map(|(name, ip, prefix_len)| {
            classify(&name).map(|kind| LocalInterface {
                name,
                ip,
                prefix_len,
                kind,
            })
        })
        .collect();
    out.sort_by(|a, b| a.kind.cmp(&b.kind).then(a.name.cmp(&b.name)));
    out.dedup_by(|a, b| a.ip == b.ip);
    out.truncate(MAX_HOSTS);
    out
}

/// Enumerates this machine's adapters.
pub fn discover() -> std::io::Result<Vec<LocalInterface>> {
    let raw = if_addrs::get_if_addrs()?
        .into_iter()
        .filter_map(|i| match i.addr {
            if_addrs::IfAddr::V4(v4) => Some((i.name, v4.ip, v4.prefixlen)),
            _ => None,
        });
    Ok(select(raw))
}

/// True for source addresses allowed to talk to the signaling server.
pub fn is_allowed_peer(ip: IpAddr, allow_loopback: bool) -> bool {
    match ip {
        IpAddr::V4(v4) => is_private_ipv4(v4) || (allow_loopback && v4.is_loopback()),
        IpAddr::V6(v6) => match v6.to_ipv4_mapped() {
            Some(v4) => is_private_ipv4(v4) || (allow_loopback && v4.is_loopback()),
            None => allow_loopback && v6.is_loopback(),
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ip(s: &str) -> Ipv4Addr {
        s.parse().unwrap()
    }

    #[test]
    fn keeps_private_physical_adapters_wifi_first() {
        let picked = select(vec![
            ("Ethernet".to_owned(), ip("192.168.1.10"), 24),
            ("vEthernet (WSL)".to_owned(), ip("172.20.0.1"), 20),
            ("WiFi".to_owned(), ip("192.168.29.42"), 24),
            ("Ethernet 2".to_owned(), ip("169.254.214.13"), 16),
            (
                "Local Area Connection* 2".to_owned(),
                ip("169.254.163.76"),
                16,
            ),
            ("Tailscale".to_owned(), ip("100.64.0.3"), 32),
            ("Loopback Pseudo-Interface 1".to_owned(), ip("127.0.0.1"), 8),
            ("Public".to_owned(), ip("8.8.8.8"), 24),
        ]);
        let ips: Vec<_> = picked.iter().map(|i| i.ip.to_string()).collect();
        assert_eq!(ips, ["192.168.29.42", "192.168.1.10"]);
        assert_eq!(picked[0].kind, InterfaceKind::Wifi);
    }

    #[test]
    fn caps_at_four_and_dedups() {
        let raw = (1..=6)
            .map(|i| (format!("Ethernet {i}"), ip(&format!("10.0.0.{i}")), 24))
            .chain([("Ethernet dup".to_owned(), ip("10.0.0.1"), 24)]);
        let picked = select(raw);
        assert_eq!(picked.len(), 4);
    }

    #[test]
    fn peer_filter() {
        assert!(is_allowed_peer("192.168.1.5".parse().unwrap(), false));
        assert!(!is_allowed_peer("8.8.8.8".parse().unwrap(), false));
        assert!(!is_allowed_peer("127.0.0.1".parse().unwrap(), false));
        assert!(is_allowed_peer("127.0.0.1".parse().unwrap(), true));
        assert!(is_allowed_peer("::ffff:10.1.2.3".parse().unwrap(), false));
        assert!(!is_allowed_peer("fe80::1".parse().unwrap(), false));
    }
}
