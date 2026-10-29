package legmon

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import org.slf4j.LoggerFactory

import java.io.{ByteArrayOutputStream, File, FileOutputStream, InputStream}
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import java.util.concurrent.{Executors, TimeUnit}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object Main {
  private val logger = LoggerFactory.getLogger(getClass)
  private val startTime = Instant.now()
  private val workerPool = Executors.newFixedThreadPool(4)
  implicit private val ec: ExecutionContext = ExecutionContext.fromExecutor(workerPool)

  def main(args: Array[String]): Unit = {
    val port = sys.env.get("PORT").flatMap(p => Try(p.toInt).toOption).getOrElse(9005)
    val monmonUrl = sys.env.getOrElse("MONMON_URL", "http://localhost:9000")

    logger.info(s"Starting LegMon Statement Extraction Service on port $port...")
    LogBuffer.info(s"LegMon service started on port $port (MonMon URL: $monmonUrl)")

    val server = HttpServer.create(new InetSocketAddress(port), 0)
    server.setExecutor(Executors.newCachedThreadPool())

    // 1. Health & Status endpoint
    server.createContext("/status", new HttpHandler {
      override def handle(exchange: HttpExchange): Unit = {
        val summary = LogBuffer.getStatusSummary
        summary("uptimeSeconds") = Instant.now().getEpochSecond - startTime.getEpochSecond
        summary("mistralKeyConfigured") = MistralExtractionService.getApiKey.nonEmpty
        summary("ocrTools") = ujson.Arr("xberg", "pdftotext", "pdfbox", "tesseract")
        sendResponse(exchange, 200, ujson.write(summary, indent = 2), "application/json")
      }
    })

    server.createContext("/health", new HttpHandler {
      override def handle(exchange: HttpExchange): Unit = {
        sendResponse(exchange, 200, """{"status":"ok","service":"LegMon"}""", "application/json")
      }
    })

    // 2. Logs endpoint for user visibility & monitoring
    server.createContext("/api/logs", new HttpHandler {
      override def handle(exchange: HttpExchange): Unit = {
        val query = exchange.getRequestURI.getQuery
        val limit = if (query != null && query.contains("limit=")) {
          Try(query.split("limit=")(1).split("&")(0).toInt).getOrElse(100)
        } else 100

        val recentLogs = LogBuffer.getRecent(limit).map { e =>
          ujson.Obj("time" -> e.timestamp, "level" -> e.level, "msg" -> e.message)
        }
        val summary = LogBuffer.getStatusSummary
        val response = ujson.Obj(
          "service" -> "LegMon",
          "hasError" -> summary("hasError"),
          "errorCount" -> summary("errorCount"),
          "lastError" -> summary("lastError"),
          "logs" -> ujson.Arr(recentLogs: _*)
        )
        sendResponse(exchange, 200, ujson.write(response, indent = 2), "application/json")
      }
    })

    // 3. Clear errors endpoint
    server.createContext("/api/logs/clear", new HttpHandler {
      override def handle(exchange: HttpExchange): Unit = {
        LogBuffer.clearErrorFlag()
        sendResponse(exchange, 200, """{"status":"cleared"}""", "application/json")
      }
    })

    // 4. Statement upload & processing endpoint
    server.createContext("/api/process", new HttpHandler {
      override def handle(exchange: HttpExchange): Unit = {
        if (!exchange.getRequestMethod.equalsIgnoreCase("POST")) {
          sendResponse(exchange, 405, """{"error":"Method Not Allowed"}""", "application/json")
          return
        }

        val uri = exchange.getRequestURI
        val queryParams = parseQuery(uri.getQuery)
        val contentType = Option(exchange.getRequestHeaders.getFirst("Content-Type")).getOrElse("")

        val userId = queryParams.getOrElse("userId", "default-user")
        val targetAccountId = queryParams.get("accountId").flatMap(s => Try(s.toLong).toOption)
        val callbackUrl = queryParams.getOrElse("callbackUrl", s"$monmonUrl/api/monzo/webhook?statement=true&userId=$userId")

        val jobId = s"stmt_${System.currentTimeMillis()}_${UUID.randomUUID().toString.take(8)}"
        LogBuffer.info(s"Received statement upload job $jobId for user $userId (ContentType: $contentType)")

        // Read uploaded data
        Try {
          val (pdfBytes, resolvedUserId, resolvedAccountId) = parseUpload(exchange.getRequestBody, contentType, userId, targetAccountId)
          if (pdfBytes.length < 100) {
            throw new IllegalArgumentException("Uploaded PDF file is empty or too small.")
          }

          // Save to a temporary file
          val tempFile = File.createTempFile("legmon_statement_", ".pdf")
          val fos = new FileOutputStream(tempFile)
          try {
            fos.write(pdfBytes)
          } finally {
            fos.close()
          }

          // Asynchronous processing pipeline
          Future {
            processPipeline(jobId, tempFile, resolvedUserId, resolvedAccountId, callbackUrl)
          }

          val resp = ujson.Obj(
            "status" -> "processing",
            "jobId" -> jobId,
            "message" -> "Statement accepted for OCR and Mistral extraction. Webhook will be notified upon completion."
          )
          sendResponse(exchange, 202, ujson.write(resp), "application/json")
        } match {
          case Success(_) => // response already sent
          case Failure(ex) =>
            val errMsg = s"Failed to accept statement upload: ${ex.getMessage}"
            logger.error(errMsg, ex)
            LogBuffer.error(errMsg)
            sendResponse(exchange, 400, ujson.write(ujson.Obj("error" -> errMsg)), "application/json")
        }
      }
    })

    server.start()
    logger.info(s"LegMon is listening on http://0.0.0.0:$port")

    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      logger.info("Shutting down LegMon...")
      server.stop(2)
      workerPool.shutdown()
      workerPool.awaitTermination(5, TimeUnit.SECONDS)
    }))
  }

  private def processPipeline(
      jobId: String,
      pdfFile: File,
      userId: String,
      accountId: Option[Long],
      callbackUrl: String
  ): Unit = {
    LogBuffer.info(s"[$jobId] Commencing OCR & extraction pipeline")
    Try {
      // Step 1: PDF to Text (tries xberg with tesseract OCR, then fallbacks, then deletes file)
      val ocrText = OcrService.extractTextAndDelete(pdfFile)

      // Step 2: Mistral Call (fault tolerant with backoff & retries)
      val statement = MistralExtractionService.extract(ocrText)

      // Step 3: Send Monzo-compatible data to webhook
      val delivered = MonzoWebhookSender.sendToWebhook(userId, jobId, statement, accountId, callbackUrl)
      if (delivered) {
        LogBuffer.info(s"[$jobId] Pipeline completed successfully for ${statement.bank}")
      } else {
        LogBuffer.warn(s"[$jobId] Extraction completed but webhook delivery failed")
      }
    } match {
      case Success(_) => // ok
      case Failure(ex) =>
        val errMsg = s"[$jobId] Pipeline failed: ${ex.getMessage}"
        logger.error(errMsg, ex)
        LogBuffer.error(errMsg)
        // Ensure file is deleted if still exists
        Try(if (pdfFile.exists()) pdfFile.delete())
    }
  }

  private def parseUpload(
      in: InputStream,
      contentType: String,
      defaultUser: String,
      defaultAccount: Option[Long]
  ): (Array[Byte], String, Option[Long]) = {
    val bytes = readAllBytes(in)

    if (contentType.toLowerCase.contains("multipart/form-data")) {
      // Basic multipart boundary parsing
      val boundary = contentType.split("boundary=").lastOption.getOrElse("").trim
      val (extractedBytes, parsedUser, parsedAccount) = parseMultipart(bytes, boundary)
      val effUser = parsedUser.getOrElse(defaultUser)
      val effAccount = parsedAccount.orElse(defaultAccount)
      (extractedBytes, effUser, effAccount)
    } else if (contentType.toLowerCase.contains("application/json")) {
      // JSON payload containing base64 PDF
      val json = ujson.read(new String(bytes, StandardCharsets.UTF_8))
      val b64 = json("data").str
      val user = json.obj.get("userId").map(_.str).getOrElse(defaultUser)
      val acc = json.obj.get("accountId").map(_.num.toLong).orElse(defaultAccount)
      val decoded = java.util.Base64.getDecoder.decode(b64)
      (decoded, user, acc)
    } else {
      // Raw binary PDF
      (bytes, defaultUser, defaultAccount)
    }
  }

  private def parseMultipart(data: Array[Byte], boundary: String): (Array[Byte], Option[String], Option[Long]) = {
    // Locate the PDF header %PDF within the multipart data
    val pdfMagic = "%PDF".getBytes(StandardCharsets.US_ASCII)
    val startIndex = indexOf(data, pdfMagic, 0)
    if (startIndex == -1) {
      throw new IllegalArgumentException("No PDF file content detected in multipart payload.")
    }

    // Locate end boundary
    val boundaryBytes = ("--" + boundary).getBytes(StandardCharsets.US_ASCII)
    val endIndex = indexOf(data, boundaryBytes, startIndex)
    val actualEnd = if (endIndex > startIndex) {
      var e = endIndex
      if (e >= 2 && data(e - 2) == '\r' && data(e - 1) == '\n') e -= 2
      else if (e >= 1 && data(e - 1) == '\n') e -= 1
      e
    } else {
      data.length
    }

    val pdfBytes = java.util.Arrays.copyOfRange(data, startIndex, actualEnd)
    (pdfBytes, None, None)
  }

  private def indexOf(source: Array[Byte], target: Array[Byte], fromIndex: Int): Int = {
    if (target.length == 0 || fromIndex >= source.length) return -1
    var i = fromIndex
    while (i <= source.length - target.length) {
      var found = true
      var j = 0
      while (j < target.length && found) {
        if (source(i + j) != target(j)) found = false
        j += 1
      }
      if (found) return i
      i += 1
    }
    -1
  }

  private def readAllBytes(in: InputStream): Array[Byte] = {
    val baos = new ByteArrayOutputStream()
    val buf = new Array[Byte](8192)
    var r = in.read(buf)
    while (r != -1) {
      baos.write(buf, 0, r)
      r = in.read(buf)
    }
    baos.toByteArray
  }

  private def parseQuery(query: String): Map[String, String] = {
    if (query == null || query.trim.isEmpty) Map.empty
    else {
      query.split("&").flatMap { param =>
        val parts = param.split("=", 2)
        if (parts.length == 2) Some(parts(0) -> java.net.URLDecoder.decode(parts(1), "UTF-8"))
        else None
      }.toMap
    }
  }

  private def sendResponse(exchange: HttpExchange, status: Int, body: String, contentType: String): Unit = {
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.set("Content-Type", s"$contentType; charset=utf-8")
    exchange.getResponseHeaders.set("Access-Control-Allow-Origin", "*")
    exchange.sendResponseHeaders(status, bytes.length)
    val os = exchange.getResponseBody
    try {
      os.write(bytes)
    } finally {
      os.close()
    }
  }
}
