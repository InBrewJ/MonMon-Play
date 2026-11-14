use std::env;
use std::fs;
use std::process::Command;

fn main() {
    // 1. Determine git commit hash
    let mut commit = env::var("BUILD_COMMIT").ok().filter(|s| !s.trim().is_empty());
    if commit.is_none() {
        commit = Command::new("git")
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
            .filter(|s| !s.is_empty());
    }

    // 2. Determine build number (rev-list count or sequential build)
    let mut build_num = env::var("BUILD_NUMBER").ok().filter(|s| !s.trim().is_empty());
    if build_num.is_none() {
        build_num = Command::new("git")
            .args(["rev-list", "--count", "HEAD"])
            .output()
            .ok()
            .and_then(|out| {
                if out.status.success() {
                    String::from_utf8(out.stdout).ok().map(|s| s.trim().to_string())
                } else {
                    None
                }
            })
            .filter(|s| !s.is_empty());
    }

    // 3. Determine build timestamp
    let mut build_time = env::var("BUILD_TIMESTAMP").ok().filter(|s| !s.trim().is_empty());

    // 4. Fallback: Check if a build.properties file exists (e.g. copied during Docker build)
    if (commit.is_none() || build_num.is_none() || build_time.is_none()) && fs::metadata("build.properties").is_ok() {
        if let Ok(content) = fs::read_to_string("build.properties") {
            for line in content.lines() {
                let line = line.trim();
                if let Some(val) = line.strip_prefix("build.commit=") {
                    if commit.is_none() && !val.trim().is_empty() {
                        commit = Some(val.trim().to_string());
                    }
                }
                if let Some(val) = line.strip_prefix("build.number=") {
                    if build_num.is_none() && !val.trim().is_empty() {
                        build_num = Some(val.trim().to_string());
                    }
                }
                if let Some(val) = line.strip_prefix("build.timestamp=") {
                    if build_time.is_none() && !val.trim().is_empty() {
                        build_time = Some(val.trim().to_string());
                    }
                }
            }
        }
    }

    if build_time.is_none() {
        build_time = Command::new("date")
            .args(["-u", "+%Y-%m-%d %H:%M:%S UTC"])
            .output()
            .ok()
            .and_then(|out| {
                if out.status.success() {
                    String::from_utf8(out.stdout).ok().map(|s| s.trim().to_string())
                } else {
                    None
                }
            })
            .filter(|s| !s.is_empty());
    }

    let final_build_time = build_time.unwrap_or_else(|| {
        use std::time::{SystemTime, UNIX_EPOCH};
        let secs = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_secs();
        format!("Epoch {}", secs)
    });

    let final_commit = commit.unwrap_or_else(|| "local".to_string());
    let final_build_num = build_num.unwrap_or_else(|| "dev".to_string());
    let final_hash = if final_commit != "local" {
        final_commit.clone()
    } else {
        use std::time::{SystemTime, UNIX_EPOCH};
        let secs = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_secs();
        format!("{:x}", secs)
    };

    println!("cargo:rustc-env=BUILD_HASH={}", final_hash);
    println!("cargo:rustc-env=BUILD_COMMIT={}", final_commit);
    println!("cargo:rustc-env=BUILD_NUMBER={}", final_build_num);
    println!("cargo:rustc-env=BUILD_TIMESTAMP={}", final_build_time);
    println!("cargo:rerun-if-changed=.git/HEAD");
    println!("cargo:rerun-if-changed=build.properties");
}
