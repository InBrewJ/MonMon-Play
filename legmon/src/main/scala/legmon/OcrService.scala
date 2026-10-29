package legmon

import java.io.File
import java.nio.file.Files
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.slf4j.LoggerFactory
import scala.sys.process._
import scala.util.Try

object OcrService {
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Extracts text from a PDF file using a fault-tolerant multi-stage pipeline:
   * 1. xberg / kreuzberg with Tesseract OCR plugin (fixed-cost OCR)
   * 2. Apache PDFBox in-JVM native stream parsing
   * 3. pdftotext CLI tool
   * 4. pdftoppm + tesseract OCR fallback for pure image scans
   *
   * Guaranteed: The temporary PDF file is deleted after extraction completes.
   */
  def extractTextAndDelete(pdfFile: File): String = {
    try {
      if (!pdfFile.exists() || pdfFile.length() == 0) {
        throw new IllegalArgumentException(s"PDF file does not exist or is empty: ${pdfFile.getAbsolutePath}")
      }

      val filePath = pdfFile.getAbsolutePath
      logger.info(s"Starting text extraction for PDF ($filePath, ${pdfFile.length()} bytes)")
      LogBuffer.info(s"Extracting text from PDF (${pdfFile.getName}, ${pdfFile.length() / 1024} KB)")

      // Strategy 1: Try xberg / kreuzberg CLI with Tesseract OCR
      val xbergResult = tryXberg(filePath)
      if (xbergResult.nonEmpty && xbergResult.get.trim.length >= 80) {
        logger.info(s"xberg OCR succeeded (${xbergResult.get.trim.length} chars)")
        LogBuffer.info(s"OCR succeeded via xberg/tesseract (${xbergResult.get.trim.length} chars)")
        return xbergResult.get
      }

      // Strategy 2: Apache PDFBox (Native digital text extraction)
      val pdfBoxResult = tryPdfBox(pdfFile)
      if (pdfBoxResult.nonEmpty && pdfBoxResult.get.trim.length >= 80) {
        logger.info(s"PDFBox digital extraction succeeded (${pdfBoxResult.get.trim.length} chars)")
        LogBuffer.info(s"Extracted text via PDFBox (${pdfBoxResult.get.trim.length} chars)")
        return pdfBoxResult.get
      }

      // Strategy 3: pdftotext CLI
      val pdftotextResult = tryPdfToText(filePath)
      if (pdftotextResult.nonEmpty && pdftotextResult.get.trim.length >= 80) {
        logger.info(s"pdftotext extraction succeeded (${pdftotextResult.get.trim.length} chars)")
        LogBuffer.info(s"Extracted text via pdftotext (${pdftotextResult.get.trim.length} chars)")
        return pdftotextResult.get
      }

      // Strategy 4: Tesseract OCR fallback for scanned images
      val tesseractResult = tryTesseractDirect(pdfFile)
      if (tesseractResult.nonEmpty && tesseractResult.get.trim.length >= 30) {
        logger.info(s"Tesseract direct OCR succeeded (${tesseractResult.get.trim.length} chars)")
        LogBuffer.info(s"Extracted text via Tesseract OCR fallback (${tesseractResult.get.trim.length} chars)")
        return tesseractResult.get
      }

      // If text was found with fewer chars, return the best available
      val best = Seq(xbergResult, pdfBoxResult, pdftotextResult, tesseractResult).flatten.maxByOption(_.length).getOrElse("")
      if (best.trim.nonEmpty) {
        logger.warn(s"OCR extracted short text (${best.trim.length} chars)")
        LogBuffer.warn(s"Short text extracted (${best.trim.length} chars)")
        best
      } else {
        val msg = s"Could not extract readable text from PDF: ${pdfFile.getName}. Scanned image may be unreadable."
        logger.error(msg)
        LogBuffer.error(msg)
        throw new RuntimeException(msg)
      }
    } finally {
      // Ensure PDF is always deleted after processing as requested
      try {
        if (pdfFile.exists()) {
          val deleted = pdfFile.delete()
          logger.info(s"Deleted processed PDF file: ${pdfFile.getAbsolutePath} (success=$deleted)")
          LogBuffer.info(s"Deleted processed PDF file: ${pdfFile.getName}")
        }
      } catch {
        case e: Exception =>
          logger.warn(s"Failed to delete PDF file: ${pdfFile.getAbsolutePath}: ${e.getMessage}")
      }
    }
  }

  private def tryXberg(filePath: String): Option[String] = {
    val binCandidates = Seq("xberg", "/usr/local/bin/xberg", "/opt/xberg/xberg", "kreuzberg")
    val binary = binCandidates.find(cmd => isCommandAvailable(cmd))

    binary.flatMap { cmd =>
      Try {
        logger.info(s"Executing $cmd extract $filePath --ocr true --ocr-backend tesseract")
        val stdout = new StringBuilder
        val stderr = new StringBuilder
        val processLogger = ProcessLogger(stdout.append(_).append("\n"), stderr.append(_).append("\n"))

        // Try xberg extract with tesseract OCR plugin (https://github.com/xberg-io/xberg/releases)
        val exitCode = Process(Seq(cmd, "extract", filePath, "--ocr", "true", "--ocr-backend", "tesseract")).!(processLogger)
        if (exitCode == 0 && stdout.nonEmpty) {
          Some(stdout.toString())
        } else {
          // Try force-ocr true
          val altStdout = new StringBuilder
          val altExit = Process(Seq(cmd, "extract", filePath, "--force-ocr", "true")).!(ProcessLogger(altStdout.append(_).append("\n"), _ => ()))
          if (altExit == 0 && altStdout.nonEmpty) Some(altStdout.toString())
          else {
            logger.warn(s"$cmd returned exit code $exitCode: ${stderr.take(200)}")
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
        val ppmExit = Process(Seq("pdftoppm", "-png", "-r", "150", pdfFile.getAbsolutePath, prefix)).!
        if (ppmExit != 0) return None

        val pngFiles = tempDir.listFiles((_, name) => name.endsWith(".png")).sorted
        val extracted = pngFiles.map { img =>
          val stdout = new StringBuilder
          val tessExit = Process(Seq("tesseract", img.getAbsolutePath, "stdout", "-l", "eng")).!(ProcessLogger(stdout.append(_).append("\n"), _ => ()))
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
