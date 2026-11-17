//! Self-signed TLS for daemons that listen beyond loopback (a Linux or WSL host on a LAN). There
//! is no CA: the pairing string carries the certificate's SHA-256 fingerprint and the app pins it.

use rustls_pki_types::{pem::PemObject, CertificateDer};
use sha2::{Digest, Sha256};
use std::{fs, path::Path};

pub struct TlsMaterial {
    pub cert_pem: String,
    pub key_pem: String,
    /// Lowercase hex SHA-256 of the certificate's DER encoding.
    pub fingerprint: String,
}

/// Loads `tls/cert.pem` and `tls/key.pem` under [config_dir], creating them on first use.
pub fn load_or_create(config_dir: &Path) -> Result<TlsMaterial, String> {
    let dir = config_dir.join("tls");
    let cert_path = dir.join("cert.pem");
    let key_path = dir.join("key.pem");
    if !cert_path.exists() || !key_path.exists() {
        let certified = rcgen::generate_simple_self_signed(vec!["avf-studio-daemon".to_owned(), "localhost".to_owned()])
            .map_err(|error| format!("cannot create a TLS certificate: {error}"))?;
        fs::create_dir_all(&dir).map_err(|error| format!("cannot create {}: {error}", dir.display()))?;
        write_private(&key_path, certified.signing_key.serialize_pem().as_bytes())?;
        fs::write(&cert_path, certified.cert.pem()).map_err(|error| format!("cannot write {}: {error}", cert_path.display()))?;
    }
    let cert_pem = fs::read_to_string(&cert_path).map_err(|error| format!("cannot read {}: {error}", cert_path.display()))?;
    let key_pem = fs::read_to_string(&key_path).map_err(|error| format!("cannot read {}: {error}", key_path.display()))?;
    let fingerprint = fingerprint(&cert_pem)?;
    Ok(TlsMaterial { cert_pem, key_pem, fingerprint })
}

pub fn fingerprint(cert_pem: &str) -> Result<String, String> {
    let der = CertificateDer::from_pem_slice(cert_pem.as_bytes()).map_err(|error| format!("invalid certificate PEM: {error}"))?;
    Ok(Sha256::digest(der.as_ref()).iter().map(|byte| format!("{byte:02x}")).collect())
}

fn write_private(path: &Path, bytes: &[u8]) -> Result<(), String> {
    let mut options = fs::OpenOptions::new();
    options.write(true).create(true).truncate(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    use std::io::Write;
    options
        .open(path)
        .and_then(|mut file| file.write_all(bytes))
        .map_err(|error| format!("cannot write {}: {error}", path.display()))
}

/// Formats a host (bracketing IPv6 literals) and port.
pub fn format_host_port(host: &str, port: u16) -> String {
    let host = if host.contains(':') && !host.starts_with('[') { format!("[{host}]") } else { host.to_owned() };
    format!("{host}:{port}")
}

/// `avf://HOST:PORT?fp=…&code=…`, pasted into the app's "Pair with a computer".
#[cfg(test)]
pub fn connection_string(host: &str, port: u16, fingerprint: &str, code: &str) -> String {
    format!("avf://{}?fp={fingerprint}&code={code}", format_host_port(host, port))
}

/// `avf://HOST:PORT?fp=public&code=…` for a daemon behind a public TLS certificate (Cloudflare Tunnel
/// or a reverse proxy with Let's Encrypt): the app verifies the certificate with Android's Root CAs.
pub fn public_connection_string(host: &str, port: u16, code: &str) -> String {
    format!("avf://{}?fp=public&code={code}", format_host_port(host, port))
}

/// Formats a single multi-route pairing string (`G37`) carrying a primary route, optional `&alt=HOST:PORT`
/// pinned routes sharing [fingerprint], and an optional `&tunnel=HOST:PORT` public-TLS route.
pub fn multi_connection_string(
    remotes: &[(String, u16)],
    fingerprint: Option<&str>,
    tunnel: Option<(&str, u16)>,
    code: &str,
) -> String {
    let Some((first_host, first_port)) = remotes.first() else {
        if let Some((tunnel_host, tunnel_port)) = tunnel {
            return public_connection_string(tunnel_host, tunnel_port, code);
        }
        return String::new();
    };
    let fp = fingerprint.unwrap_or("public");
    let mut url = format!("avf://{}?fp={fp}&code={code}", format_host_port(first_host, *first_port));
    for (alt_host, alt_port) in remotes.iter().skip(1) {
        url.push_str("&alt=");
        url.push_str(&format_host_port(alt_host, *alt_port));
    }
    if let Some((tunnel_host, tunnel_port)) = tunnel {
        url.push_str("&tunnel=");
        url.push_str(&format_host_port(tunnel_host, tunnel_port));
    }
    url
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn certificate_is_created_once_and_fingerprinted() {
        let dir = tempfile::tempdir().unwrap();
        let first = load_or_create(dir.path()).unwrap();
        let second = load_or_create(dir.path()).unwrap();
        assert_eq!(first.fingerprint, second.fingerprint);
        assert_eq!(first.fingerprint.len(), 64);
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = fs::metadata(dir.path().join("tls/key.pem")).unwrap().permissions().mode();
            assert_eq!(mode & 0o777, 0o600);
        }
    }

    #[test]
    fn connection_strings_bracket_ipv6_hosts() {
        assert_eq!(connection_string("192.168.1.10", 19870, "ab", "K7QM-2XPA"), "avf://192.168.1.10:19870?fp=ab&code=K7QM-2XPA");
        assert_eq!(connection_string("fe80::1", 19870, "ab", "C"), "avf://[fe80::1]:19870?fp=ab&code=C");
        assert_eq!(public_connection_string("quirecode.example.com", 443, "K7QM-2XPA"), "avf://quirecode.example.com:443?fp=public&code=K7QM-2XPA");
        assert_eq!(
            multi_connection_string(
                &[("192.168.1.10".to_owned(), 19870), ("100.64.0.1".to_owned(), 19870)],
                Some("ab"),
                Some(("quirecode.example.com", 443)),
                "K7QM-2XPA",
            ),
            "avf://192.168.1.10:19870?fp=ab&code=K7QM-2XPA&alt=100.64.0.1:19870&tunnel=quirecode.example.com:443",
        );
    }
}
