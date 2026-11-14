use base64::Engine;
use jsonwebtoken::EncodingKey;
use rsa::pkcs8::{DecodePrivateKey, EncodePrivateKey};
use rsa::traits::PublicKeyParts;
use rsa::RsaPrivateKey;
use std::fs;
use std::path::Path;

#[derive(Clone)]
pub struct KeyManager {
    pub kid: String,
    pub n_b64: String,
    pub e_b64: String,
    pub encoding_key: EncodingKey,
}

impl KeyManager {
    pub fn new_or_load(key_path: &str) -> Self {
        let path = Path::new(key_path);
        let priv_key = if path.exists() {
            tracing::info!("Loading existing RSA private key from {}", key_path);
            let pem = fs::read_to_string(path).expect("failed to read private key pem");
            RsaPrivateKey::from_pkcs8_pem(&pem).expect("failed to parse private key pem")
        } else {
            tracing::info!("Generating new 2048-bit RSA key pair...");
            if let Some(parent) = path.parent() {
                let _ = fs::create_dir_all(parent);
            }
            let mut rng = rand::rngs::OsRng;
            let key = RsaPrivateKey::new(&mut rng, 2048).expect("failed to generate RSA key");
            let pem = key.to_pkcs8_pem(rsa::pkcs8::LineEnding::LF).expect("failed to encode pem");
            fs::write(path, pem.as_str()).expect("failed to write private key pem");
            tracing::info!("Saved new RSA private key to {}", key_path);
            key
        };

        let pub_key = priv_key.to_public_key();
        let n_bytes = pub_key.n().to_bytes_be();
        let e_bytes = pub_key.e().to_bytes_be();

        let n_b64 = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(&n_bytes);
        let e_b64 = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(&e_bytes);

        let pem = priv_key.to_pkcs8_pem(rsa::pkcs8::LineEnding::LF).expect("failed to encode pem");
        let encoding_key = EncodingKey::from_rsa_pem(pem.as_bytes()).expect("failed to create encoding key");

        let kid = "monmon-auth-key-1".to_string();

        KeyManager {
            kid,
            n_b64,
            e_b64,
            encoding_key,
        }
    }
}
