package legmon

import java.io.File
import java.nio.file.Files
import java.util.Base64
import org.slf4j.LoggerFactory
import scala.util.{Failure, Success, Try}

/**
 * Mistral Document AI OCR (POST /v1/ocr) - converts a PDF into per-page markdown.
 *
 * Why this is the primary text source:
 * Benchmarked against a deterministic ground truth on the NatWest "Your transactions" export
 * (legmon/scripts/statement_accuracy_bench.py), Mistral OCR preserves table rows as markdown
 * (| Date | Description | Type | Paid in | Paid out |), so the downstream chat model sees each
 * amount in its correct column. It was penny-perfect (78/78 rows, paid in AND paid out) on
 * mistral-small, -medium and -large. xberg/tesseract emits the same page column-by-column
 * (all dates, then all descriptions, then all amounts), which breaks row alignment and caused
 * most "Paid in" credits to be dropped or mis-signed.
 */
object MistralOcrService {
  private val logger = LoggerFactory.getLogger(getClass)

  def getOcrModel: String =
    sys.env.get("MISTRAL_OCR_MODEL").orElse(sys.props.get("MISTRAL_OCR_MODEL")).getOrElse("mistral-ocr-latest").trim

  def isEnabled: Boolean = {
    val disabled = sys.env.get("MISTRAL_OCR_ENABLED").exists(v => v.trim.equalsIgnoreCase("false") || v.trim == "0")
    !disabled && MistralExtractionService.getApiKey.nonEmpty
  }

  /** Returns the concatenated markdown of all pages, or None on any failure (caller falls back to local OCR). */
  def extractMarkdown(pdfFile: File, maxRetries: Int = 3): Option[String] = {
    val apiKey = MistralExtractionService.getApiKey
    if (apiKey.isEmpty) return None

    val b64 = Base64.getEncoder.encodeToString(Files.readAllBytes(pdfFile.toPath))
    val payload = ujson.Obj(
      "model" -> getOcrModel,
      "document" -> ujson.Obj(
        "type" -> "document_url",
        "document_url" -> s"data:application/pdf;base64,$b64"
      ),
      "include_image_base64" -> false
    )

    var attempt = 1
    var delayMs = 2000L
    while (attempt <= maxRetries) {
      Try {
        val res = requests.post(
          "https://api.mistral.ai/v1/ocr",
          headers = Seq("Authorization" -> s"Bearer $apiKey", "Content-Type" -> "application/json"),
          data = ujson.write(payload),
          readTimeout = 120000,
          connectTimeout = 15000,
          check = false
        )
        if (res.statusCode != 200) throw new RuntimeException(s"Mistral OCR returned HTTP ${res.statusCode}: ${res.text().take(300)}")
        val pages = ujson.read(res.text())("pages").arr
        pages.map(p => p.obj.get("markdown").map(_.str).getOrElse("")).mkString("\n\n")
      } match {
        case Success(md) if md.trim.nonEmpty =>
          logger.info(s"Mistral OCR ($getOcrModel) succeeded (${md.length} chars)")
          return Some(md)
        case Success(_) =>
          logger.warn("Mistral OCR returned empty markdown")
          return None
        case Failure(ex) =>
          val msg = ex.getMessage
          val retryable = Seq("429", "500", "502", "503", "504", "imeout", "onnect").exists(msg.contains)
          logger.warn(s"Mistral OCR attempt $attempt/$maxRetries failed: $msg")
          LogBuffer.warn(s"Mistral OCR attempt $attempt/$maxRetries failed: ${msg.take(160)}")
          if (!retryable || attempt == maxRetries) return None
          Thread.sleep(delayMs)
          delayMs *= 2
          attempt += 1
      }
    }
    None
  }
}
