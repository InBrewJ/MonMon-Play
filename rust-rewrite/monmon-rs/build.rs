fn main() {
    let git_hash = std::process::Command::new("git")
        .args(["rev-parse", "--short", "HEAD"])
        .output()
        .ok()
        .and_then(|out| {
            if out.status.success() {
                String::from_utf8(out.stdout).ok().map(|s| s.trim().to_string())
            } else {
                None
            }
        })
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| {
            use std::time::{SystemTime, UNIX_EPOCH};
            let secs = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_secs();
            format!("{:x}", secs)
        });

    println!("cargo:rustc-env=BUILD_HASH={}", git_hash);
    println!("cargo:rerun-if-changed=.git/HEAD");
}
