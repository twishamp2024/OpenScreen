//! Ephemeral TLS identity (docs/PROTOCOL.md §3).
//!
//! A new self-signed ECDSA P-256 certificate is generated at every start and
//! never written to disk. Its SHA-256 fingerprint goes into the QR code; the
//! phone accepts no other certificate.

use std::net::Ipv4Addr;
use std::sync::Arc;

use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use rustls::ServerConfig;
use sha2::{Digest, Sha256};

pub struct TlsIdentity {
    pub config: Arc<ServerConfig>,
    /// base64url(SHA-256(DER)), 43 characters.
    pub fingerprint: String,
    pub certificate_der: Vec<u8>,
}

pub fn fingerprint_of(der: &[u8]) -> String {
    URL_SAFE_NO_PAD.encode(Sha256::digest(der))
}

pub fn provider() -> Arc<rustls::crypto::CryptoProvider> {
    Arc::new(rustls::crypto::ring::default_provider())
}

pub fn generate(ips: &[Ipv4Addr]) -> Result<TlsIdentity, String> {
    let mut sans: Vec<String> = ips.iter().map(Ipv4Addr::to_string).collect();
    sans.push("openscreen.local".to_owned());
    let key =
        rcgen::KeyPair::generate_for(&rcgen::PKCS_ECDSA_P256_SHA256).map_err(|e| e.to_string())?;
    let mut params = rcgen::CertificateParams::new(sans).map_err(|e| e.to_string())?;
    params
        .distinguished_name
        .push(rcgen::DnType::CommonName, "OpenScreen Receiver");
    let cert = params.self_signed(&key).map_err(|e| e.to_string())?;
    let der = cert.der().to_vec();

    let config = ServerConfig::builder_with_provider(provider())
        .with_protocol_versions(&[&rustls::version::TLS13])
        .map_err(|e| e.to_string())?
        .with_no_client_auth()
        .with_single_cert(
            vec![CertificateDer::from(der.clone())],
            PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(key.serialize_der())),
        )
        .map_err(|e| e.to_string())?;

    Ok(TlsIdentity {
        config: Arc::new(config),
        fingerprint: fingerprint_of(&der),
        certificate_der: der,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generates_distinct_identities_with_43_char_fingerprints() {
        let a = generate(&["192.168.1.20".parse().unwrap()]).unwrap();
        let b = generate(&["192.168.1.20".parse().unwrap()]).unwrap();
        assert_eq!(a.fingerprint.len(), 43);
        assert_ne!(a.fingerprint, b.fingerprint);
        assert_eq!(fingerprint_of(&a.certificate_der), a.fingerprint);
    }
}
