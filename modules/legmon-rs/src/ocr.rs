use std::path::Path;
use std::process::Command;
use tracing::info;

pub fn extract_text_pdftotext(pdf_path: &Path) -> Result<String, String> {
    let output = Command::new("pdftotext")
        .arg("-layout")
        .arg(pdf_path)
        .arg("-")
        .output()
        .map_err(|e| format!("Failed to execute pdftotext: {}", e))?;

    if !output.status.success() {
        return Err(format!(
            "pdftotext exited with status {}: {}",
            output.status,
            String::from_utf8_lossy(&output.stderr)
        ));
    }

    Ok(String::from_utf8_lossy(&output.stdout).to_string())
}

pub fn try_xberg(pdf_path: &Path) -> Option<String> {
    let bin_candidates = ["xberg", "/usr/local/bin/xberg", "/opt/xberg/xberg", "kreuzberg"];
    let binary = bin_candidates.iter().find(|&&cmd| is_command_available(cmd))?;

    info!("Attempting xberg extraction on {:?} with {}", pdf_path, binary);

    // 1. Prefer pdfium backend for digital PDFs (preserves horizontal layout)
    let out = Command::new(binary)
        .arg("extract")
        .arg(pdf_path)
        .arg("--pdf-backend")
        .arg("pdfium")
        .output()
        .ok()?;

    if out.status.success() {
        let text = String::from_utf8_lossy(&out.stdout).to_string();
        if text.trim().len() >= 80 {
            return Some(text);
        }
    }

    // 2. Fallback to OCR mode with tesseract psm 6 for scanned PDFs
    let ocr_out = Command::new(binary)
        .arg("extract")
        .arg(pdf_path)
        .arg("--force-ocr")
        .arg("true")
        .arg("--ocr-backend")
        .arg("tesseract")
        .arg("--target-dpi")
        .arg("300")
        .arg("--config-json")
        .arg(r#"{"ocr":{"backend":"tesseract","tesseract_config":{"psm":6}}}"#)
        .output()
        .ok()?;

    if ocr_out.status.success() {
        let text = String::from_utf8_lossy(&ocr_out.stdout).to_string();
        if text.trim().len() >= 80 {
            return Some(text);
        }
    }

    None
}

pub fn extract_text(pdf_path: &Path) -> (Result<String, String>, String) {
    let pdftotext_res = extract_text_pdftotext(pdf_path);
    match pdftotext_res {
        Ok(ref txt) if txt.trim().len() >= 80 => (pdftotext_res, "pdftotext -layout".to_string()),
        Ok(ref txt) => {
            // Text is very short, might be scanned image. Try xberg if present.
            if let Some(xberg_txt) = try_xberg(pdf_path) {
                (Ok(xberg_txt), "xberg-native".to_string())
            } else {
                (Ok(txt.clone()), "pdftotext -layout".to_string())
            }
        }
        Err(e) => {
            if let Some(xberg_txt) = try_xberg(pdf_path) {
                (Ok(xberg_txt), "xberg-native".to_string())
            } else {
                (Err(e), "none".to_string())
            }
        }
    }
}

fn is_command_available(cmd: &str) -> bool {
    Command::new("which")
        .arg(cmd)
        .output()
        .map(|o| o.status.success())
        .unwrap_or(false)
}
