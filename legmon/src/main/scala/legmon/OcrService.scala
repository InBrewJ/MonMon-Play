package legmon

import java.io.File
import java.nio.file.Files
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.slf4j.LoggerFactory
import scala.sys.process._
import scala.util.Try

case class OcrExtractionResult(
    text: String,
    strategy: String,
    recommendedModel: String
)

object OcrService {
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Extracts text from a PDF file and returns the matched OCR strategy + recommended Mistral model:
   * - Primary (Pipeline B): Mistral Document AI OCR (/v1/ocr, mistral-ocr-latest) -> mistral-small-latest
   * - Fallback (Pipeline A2): pdftotext -layout (and local OCR fallbacks for scans) -> mistral-medium-latest
   *
   * Guaranteed: The temporary PDF file is deleted after extraction completes.
   */
  def extractWithStrategyAndDelete(pdfFile: File): OcrExtractionResult = {
    try {
      extractWithStrategyKeepFile(pdfFile, allowMistralOcr = MistralOcrService.isEnabled)
    } finally {
      deleteQuietly(pdfFile)
    }
  }

  def extractTextAndDelete(pdfFile: File): String = {
    extractWithStrategyAndDelete(pdfFile).text
  }

  def extractWithStrategyKeepFile(pdfFile: File, allowMistralOcr: Boolean): OcrExtractionResult = {
    if (!pdfFile.exists() || pdfFile.length() == 0) {
      throw new IllegalArgumentException(s"PDF file does not exist or is empty: ${pdfFile.getAbsolutePath}")
    }

    val filePath = pdfFile.getAbsolutePath
    logger.info(s"Starting text extraction for PDF ($filePath, ${pdfFile.length()} bytes)")
    LogBuffer.info(s"Extracting text from PDF (${pdfFile.getName}, ${pdfFile.length() / 1024} KB)")

    val forceLegacyXberg = sys.env.get("LEGMON_FORCE_XBERG_NATIVE").exists(v => v.trim.equalsIgnoreCase("true") || v.trim == "1")
    if (forceLegacyXberg) {
      val legacyXberg = tryXbergNative(filePath)
      if (legacyXberg.exists(_.trim.length >= 80)) {
        LogBuffer.info(s"OCR succeeded via legacy xberg native (${legacyXberg.get.trim.length} chars)")
        return OcrExtractionResult(legacyXberg.get, "xberg-native", MistralExtractionService.getPrimaryModel)
      }
    }

    // Primary (Pipeline B): Mistral Document AI OCR (row-preserving markdown tables) -> mistral-small-latest
    if (allowMistralOcr && MistralOcrService.isEnabled) {
      val mistralMd = MistralOcrService.extractMarkdown(pdfFile)
      if (mistralMd.exists(_.trim.length >= 80)) {
        val primaryModel = MistralExtractionService.getPrimaryModel
        LogBuffer.info(s"Primary Pipeline B OCR succeeded via Mistral OCR (${mistralMd.get.trim.length} chars) -> model $primaryModel")
        return OcrExtractionResult(mistralMd.get, "mistral-ocr", primaryModel)
      }
      LogBuffer.warn("Mistral OCR unavailable - falling back to pdftotext -layout with mistral-medium-latest")
    }

    val fallbackModel = MistralExtractionService.getFallbackModel

    // Fallback 1 (Pipeline A2): pdftotext -layout (preserves exact row & column whitespace) -> mistral-medium-latest
    val pdftotextResult = tryPdfToText(filePath)
    if (pdftotextResult.nonEmpty && pdftotextResult.get.trim.length >= 80) {
      logger.info(s"Fallback pdftotext -layout extraction succeeded (${pdftotextResult.get.trim.length} chars) -> model $fallbackModel")
      LogBuffer.info(s"Fallback extracted text via pdftotext -layout (${pdftotextResult.get.trim.length} chars) -> model $fallbackModel")
      return OcrExtractionResult(pdftotextResult.get, "pdftotext-layout", fallbackModel)
    }

    // Fallback 2: xberg / kreuzberg CLI (using row-preserving pdfium backend first, then tuned Tesseract PSM 6)
    val xbergResult = tryXberg(filePath)
    if (xbergResult.nonEmpty && xbergResult.get.trim.length >= 80) {
      logger.info(s"xberg OCR succeeded (${xbergResult.get.trim.length} chars) -> model $fallbackModel")
      LogBuffer.info(s"OCR succeeded via xberg (${xbergResult.get.trim.length} chars) -> model $fallbackModel")
      return OcrExtractionResult(xbergResult.get, "xberg", fallbackModel)
    }

    // Fallback 3: Apache PDFBox (Native digital text extraction with sortByPosition=true)
    val pdfBoxResult = tryPdfBox(pdfFile)
    if (pdfBoxResult.nonEmpty && pdfBoxResult.get.trim.length >= 80) {
      logger.info(s"PDFBox digital extraction succeeded (${pdfBoxResult.get.trim.length} chars) -> model $fallbackModel")
      LogBuffer.info(s"Extracted text via PDFBox (${pdfBoxResult.get.trim.length} chars) -> model $fallbackModel")
      return OcrExtractionResult(pdfBoxResult.get, "pdfbox", fallbackModel)
    }

    // Fallback 4: Tesseract OCR fallback for scanned images (300 DPI + PSM 6 + preserve_interword_spaces=1)
    val tesseractResult = tryTesseractDirect(pdfFile)
    if (tesseractResult.nonEmpty && tesseractResult.get.trim.length >= 30) {
      logger.info(s"Tesseract direct OCR succeeded (${tesseractResult.get.trim.length} chars) -> model $fallbackModel")
      LogBuffer.info(s"Extracted text via Tesseract OCR fallback (${tesseractResult.get.trim.length} chars) -> model $fallbackModel")
      return OcrExtractionResult(tesseractResult.get, "tesseract-psm6", fallbackModel)
    }

    // If text was found with fewer chars, return the best available
    val best = Seq(pdftotextResult, xbergResult, pdfBoxResult, tesseractResult).flatten.maxByOption(_.length).getOrElse("")
    if (best.trim.nonEmpty) {
      logger.warn(s"OCR extracted short text (${best.trim.length} chars)")
      LogBuffer.warn(s"Short text extracted (${best.trim.length} chars)")
      OcrExtractionResult(best, "short-fallback", fallbackModel)
    } else {
      val msg = s"Could not extract readable text from PDF: ${pdfFile.getName}. Scanned image may be unreadable."
      logger.error(msg)
      LogBuffer.error(msg)
      throw new RuntimeException(msg)
    }
  }

  def deleteQuietly(pdfFile: File): Unit = {
    try {
      if (pdfFile != null && pdfFile.exists()) {
        val deleted = pdfFile.delete()
        logger.info(s"Deleted processed PDF file: ${pdfFile.getAbsolutePath} (success=$deleted)")
        LogBuffer.info(s"Deleted processed PDF file: ${pdfFile.getName}")
      }
    } catch {
      case e: Exception =>
        logger.warn(s"Failed to delete PDF file: ${pdfFile.getAbsolutePath}: ${e.getMessage}")
    }
  }

  private def tryXbergNative(filePath: String): Option[String] = {
    val binCandidates = Seq("xberg", "/usr/local/bin/xberg", "/opt/xberg/xberg", "kreuzberg")
    binCandidates.find(isCommandAvailable).flatMap { cmd =>
      Try {
        val stdout = new StringBuilder
        val exitCode = Process(Seq(cmd, "extract", filePath, "--ocr", "true", "--ocr-backend", "tesseract")).!(
          ProcessLogger(stdout.append(_).append("\n"), _ => ())
        )
        if (exitCode == 0 && stdout.nonEmpty) Some(stdout.toString()) else None
      }.toOption.flatten
    }
  }

  private def tryXberg(filePath: String): Option[String] = {
    val binCandidates = Seq("xberg", "/usr/local/bin/xberg", "/opt/xberg/xberg", "kreuzberg")
    val binary = binCandidates.find(cmd => isCommandAvailable(cmd))

    binary.flatMap { cmd =>
      Try {
        // 1. Prefer --pdf-backend pdfium for digital PDFs so xberg preserves horizontal rows instead of splitting columns vertically
        logger.info(s"Executing $cmd extract $filePath --pdf-backend pdfium")
        val pdfiumOut = new StringBuilder
        val pdfiumExit = Process(Seq(cmd, "extract", filePath, "--pdf-backend", "pdfium")).!(
          ProcessLogger(pdfiumOut.append(_).append("\n"), _ => ())
        )
        if (pdfiumExit == 0 && pdfiumOut.toString().trim.length >= 80) {
          Some(pdfiumOut.toString())
        } else {
          // 2. For scanned PDFs, use Tesseract with PSM 6 (single uniform block) at 300 DPI to prevent column-splitting
          val ocrOut = new StringBuilder
          val stderr = new StringBuilder
          val ocrExit = Process(Seq(
            cmd, "extract", filePath,
            "--force-ocr", "true",
            "--ocr-backend", "tesseract",
            "--target-dpi", "300",
            "--pdf-extract-tables", "false",
            "--pdf-ocr-inline-images", "false",
            "--config-json", """{"ocr":{"backend":"tesseract","tesseract_config":{"psm":6}}}"""
          )).!(ProcessLogger(ocrOut.append(_).append("\n"), stderr.append(_).append("\n")))
          if (ocrExit == 0 && ocrOut.nonEmpty) Some(ocrOut.toString())
          else {
            logger.warn(s"$cmd returned exit code $ocrExit: ${stderr.take(200)}")
            None
          }
        }
      }.toOption.flatten
    }
  }

  private def tryPdfBox(pdfFile: File): Option[String] = {
    Try {
      val doc = PDDocument.load(pdfFile)
      try {
        val stripper = new PDFTextStripper()
        stripper.setSortByPosition(true)
        val text = stripper.getText(doc)
        if (text != null && text.trim.nonEmpty) Some(text) else None
      } finally {
        doc.close()
      }
    }.toOption.flatten
  }

  private def tryPdfToText(filePath: String): Option[String] = {
    if (!isCommandAvailable("pdftotext")) return None
    Try {
      val stdout = new StringBuilder
      val exitCode = Process(Seq("pdftotext", "-layout", filePath, "-")).!(ProcessLogger(stdout.append(_).append("\n"), _ => ()))
      if (exitCode == 0 && stdout.nonEmpty) Some(stdout.toString()) else None
    }.toOption.flatten
  }

  private def tryTesseractDirect(pdfFile: File): Option[String] = {
    if (!isCommandAvailable("tesseract") || !isCommandAvailable("pdftoppm")) return None
    Try {
      val tempDir = Files.createTempDirectory("legmon-ocr-").toFile
      try {
        val prefix = new File(tempDir, "page").getAbsolutePath
        val ppmExit = Process(Seq("pdftoppm", "-png", "-r", "300", pdfFile.getAbsolutePath, prefix)).!
        if (ppmExit != 0) return None

        val pngFiles = tempDir.listFiles((_, name) => name.endsWith(".png")).sorted
        val extracted = pngFiles.map { img =>
          val stdout = new StringBuilder
          // PSM 6 assumes a single uniform block of text (prevents vertical column splitting on tables)
          val tessExit = Process(Seq(
            "tesseract", img.getAbsolutePath, "stdout",
            "-l", "eng", "--psm", "6", "-c", "preserve_interword_spaces=1"
          )).!(ProcessLogger(stdout.append(_).append("\n"), _ => ()))
          if (tessExit == 0) stdout.toString() else ""
        }.mkString("\n")

        if (extracted.trim.nonEmpty) Some(extracted) else None
      } finally {
        // Clean up temp images
        tempDir.listFiles().foreach(_.delete())
        tempDir.delete()
      }
    }.toOption.flatten
  }

  private def isCommandAvailable(cmd: String): Boolean = {
    Try {
      val p = Process(Seq("which", cmd)).!(ProcessLogger(_ => (), _ => ()))
      p == 0 || new File(cmd).canExecute
    }.getOrElse(false)
  }
}
