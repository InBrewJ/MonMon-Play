use std::path::Path;
use std::process::Command;

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
