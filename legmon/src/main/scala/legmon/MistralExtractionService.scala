package legmon

import org.slf4j.LoggerFactory
import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

case class ExtractedTransaction(
    id: String,
    date: String,
    description: String,
    amount: Float, // Positive for spend, negative for credit/income
    merchant: String,
    category: String
)

case class ExtractedBalance(
    date: String,
    value: Float
)

case class ExtractedStatement(
    bank: String,
    startDate: String,
    endDate: String,
    openingBalance: Float,
    closingBalance: Float,
    balances: Seq[ExtractedBalance],
    transactions: Seq[ExtractedTransaction]
)

object MistralExtractionService {
  private val logger = LoggerFactory.getLogger(getClass)

  private val ConfCandidatePaths: Seq[String] = Seq(
    "/app/secureProd.conf",
    "conf/secureProd.conf",
    "../conf/secureProd.conf"
  )

  private def readKeyFromFile(path: String): Option[String] = {
    Try {
      val file = new java.io.File(path)
      if (file.exists() && file.isFile) {
        val src = scala.io.Source.fromFile(file)
        try {
          val content = src.mkString
          val pattern = """apiKey\s*=\s*"([^"]+)"""".r
          pattern.findFirstMatchIn(content).map(_.group(1).trim)
        } finally {
          src.close()
        }
      } else None
    }.toOption.flatten
  }

  private def readModelFromFile(path: String): Option[String] = {
    Try {
      val file = new java.io.File(path)
      if (file.exists() && file.isFile) {
        val src = scala.io.Source.fromFile(file)
        try {
          val content = src.mkString
          val pattern = """model\s*=\s*"([^"]+)"""".r
          pattern.findFirstMatchIn(content).map(_.group(1).trim)
        } finally {
          src.close()
        }
      } else None
    }.toOption.flatten
  }

  def getApiKey: String = {
    sys.env.get("MISTRAL_API_KEY")
      .orElse(sys.props.get("MISTRAL_API_KEY"))
      .orElse(ConfCandidatePaths.flatMap(readKeyFromFile).headOption)
      .getOrElse("")
      .trim
  }

  def getModel: String = {
    sys.env.get("MISTRAL_MODEL")
      .orElse(sys.props.get("MISTRAL_MODEL"))
      .orElse(ConfCandidatePaths.flatMap(readModelFromFile).headOption)
      .getOrElse("mistral-small-latest")
      .trim
  }

  def extract(text: String): ExtractedStatement = {
    val apiKey = getApiKey
    if (apiKey.isEmpty) {
      val err = "Mistral API key is not configured. Set MISTRAL_API_KEY environment variable."
      logger.error(err)
      LogBuffer.error(err)
      throw new IllegalStateException(err)
    }

    val trimmedText = if (text.length > 50000) text.take(50000) else text
    LogBuffer.info(s"Calling Mistral (${getModel}) to extract statement data (${trimmedText.length} chars of OCR text)")

    val prompt =
      """You are an expert UK banking document and financial statement parsing assistant.
        |Analyze the raw OCR text of this bank statement and extract structured data in JSON format.
        |
        |You MUST return ONLY valid JSON matching this exact structure:
        |{
        |  "bank": "Name of the bank or institution (e.g. Lloyds Bank, Barclays, American Express, Nationwide, Chase, HSBC, Santander, NatWest, Monzo)",
        |  "startDate": "YYYY-MM-DD",
        |  "endDate": "YYYY-MM-DD",
        |  "openingBalance": 123.45,
        |  "closingBalance": 456.78,
        |  "balances": [
        |    { "date": "YYYY-MM-DD", "value": 456.78 }
        |  ],
        |  "transactions": [
        |    {
        |      "date": "YYYY-MM-DD",
        |      "description": "Clean description of the transaction",
        |      "merchant": "Identified merchant name or clean payee",
        |      "amount": 12.50,
        |      "category": "groceries | dining | shopping | bills | transport | entertainment | general"
        |    }
        |  ]
        |}
        |
        |IMPORTANT AMOUNT CONVENTION RULES:
        |- In MonMon, spending/expenses MUST be positive numbers (e.g. 15.00 for spending £15.00 at Tesco).
        |- In MonMon, deposits, salary, refunds, or incoming credits MUST be negative numbers (e.g. -1500.00 for salary).
        |- For balances: positive number indicates money in bank / credit available.
        |
        |Extract every valid transaction present in the statement text with the accurate date and amount.
        |Do not wrap in markdown quotes if possible, output pure JSON.
        |""".stripMargin

    val payload = ujson.Obj(
      "model" -> getModel,
      "messages" -> ujson.Arr(
        ujson.Obj("role" -> "system", "content" -> prompt),
        ujson.Obj("role" -> "user", "content" -> s"Bank Statement Raw OCR Text:\n\n$trimmedText")
      ),
      "response_format" -> ujson.Obj("type" -> "json_object"),
      "temperature" -> 0.1
    )

    executeWithRetry(payload, maxRetries = 4, currentAttempt = 1, delayMs = 2000)
  }

  @tailrec
  private def executeWithRetry(payload: ujson.Obj, maxRetries: Int, currentAttempt: Int, delayMs: Long): ExtractedStatement = {
    val apiKey = getApiKey
    val attemptTry = Try {
      val res = requests.post(
        "https://api.mistral.ai/v1/chat/completions",
        headers = Seq(
          "Authorization" -> s"Bearer $apiKey",
          "Content-Type" -> "application/json"
        ),
        data = ujson.write(payload),
        readTimeout = 60000,
        connectTimeout = 15000
      )

      if (res.statusCode != 200) {
        throw new RuntimeException(s"Mistral API returned HTTP ${res.statusCode}: ${res.text()}")
      }

      val json = ujson.read(res.text())
      val contentStr = json("choices")(0)("message")("content").str
      try {
        parseExtractedJson(contentStr)
      } catch {
        case ex: Throwable =>
          logger.error(s"Failed to parse Mistral JSON output: ${ex.getMessage}. Raw content was: $contentStr")
          LogBuffer.error(s"Failed to parse Mistral JSON output: ${ex.getMessage}")
          throw ex
      }
    }

    attemptTry match {
      case Success(stmt) =>
        logger.info(s"Mistral extraction succeeded! Bank='${stmt.bank}', Period=${stmt.startDate} to ${stmt.endDate}, Transactions=${stmt.transactions.size}")
        LogBuffer.info(s"Mistral extracted Bank='${stmt.bank}', ${stmt.transactions.size} transactions, Closing Balance=£${stmt.closingBalance}")
        stmt

      case Failure(ex) =>
        val isRetryable = currentAttempt < maxRetries && (
          ex.getMessage.contains("429") ||
          ex.getMessage.contains("500") ||
          ex.getMessage.contains("502") ||
          ex.getMessage.contains("503") ||
          ex.getMessage.contains("504") ||
          ex.getMessage.contains("Timeout") ||
          ex.getMessage.contains("Connection")
        )

        val warnMsg = s"Mistral call attempt $currentAttempt/$maxRetries failed: ${ex.getMessage}"
        logger.warn(warnMsg)
        LogBuffer.warn(warnMsg)

        if (isRetryable) {
          LogBuffer.info(s"Retrying Mistral API call in ${delayMs / 1000}s (exponential backoff)...")
          Thread.sleep(delayMs)
          executeWithRetry(payload, maxRetries, currentAttempt + 1, delayMs * 2)
        } else {
          val errMsg = s"Mistral extraction failed after $currentAttempt attempts: ${ex.getMessage}"
          logger.error(errMsg, ex)
          LogBuffer.error(errMsg)
          throw new RuntimeException(errMsg, ex)
        }
    }
  }

  private def extractString(v: Option[ujson.Value], default: String = ""): String = {
    v match {
      case Some(ujson.Str(s)) if s != null => s.trim
      case Some(ujson.Num(n)) => if (n == n.toLong) n.toLong.toString else n.toString
      case Some(ujson.Bool(b)) => b.toString
      case _ => default
    }
  }

  private def extractFloat(v: Option[ujson.Value], default: Float = 0.0f): Float = {
    v match {
      case Some(ujson.Num(n)) => n.toFloat
      case Some(ujson.Str(s)) if s != null && s.trim.nonEmpty =>
        Try(s.replaceAll("[^0-9.-]", "").toFloat).getOrElse(default)
      case _ => default
    }
  }

  private def parseExtractedJson(rawContent: String): ExtractedStatement = {
    // Strip possible markdown fences
    val cleaned = rawContent.trim
      .stripPrefix("```json")
      .stripPrefix("```")
      .stripSuffix("```")
      .trim

    val json = ujson.read(cleaned)
    val bank = extractString(json.obj.get("bank"), "Unknown Bank")
    val defaultEndDate = java.time.LocalDate.now().toString
    val defaultStartDate = java.time.LocalDate.now().minusDays(30).toString
    val startDate = extractString(json.obj.get("startDate"), defaultStartDate)
    val endDate = extractString(json.obj.get("endDate"), defaultEndDate)
    val openingBalance = extractFloat(json.obj.get("openingBalance"), 0.0f)
    val closingBalance = extractFloat(json.obj.get("closingBalance"), 0.0f)

    val balances = json.obj.get("balances").flatMap {
      case ujson.Arr(arr) =>
        Some(arr.flatMap { b =>
          Try {
            val bDate = extractString(b.obj.get("date"), endDate)
            val bVal = extractFloat(b.obj.get("value"), closingBalance)
            ExtractedBalance(bDate, bVal)
          }.toOption
        }.toSeq)
      case _ => None
    }.getOrElse(Seq(ExtractedBalance(endDate, closingBalance)))

    val transactions = json.obj.get("transactions").flatMap {
      case ujson.Arr(arr) =>
        Some(arr.zipWithIndex.flatMap { case (t, idx) =>
          Try {
            val desc = extractString(t.obj.get("description"), "Statement Transaction")
            val date = extractString(t.obj.get("date"), endDate)
            val merchant = extractString(t.obj.get("merchant"), desc)
            val amount = extractFloat(t.obj.get("amount"), 0.0f)
            val cat = extractString(t.obj.get("category"), "General")
            val txId = s"legmon_tx_${System.currentTimeMillis()}_$idx"

            ExtractedTransaction(
              id = txId,
              date = date,
              description = desc,
              amount = amount,
              merchant = if (merchant.nonEmpty) merchant else desc,
              category = if (cat.nonEmpty) cat else "General"
            )
          }.toOption
        }.toSeq)
      case _ => None
    }.getOrElse(Seq.empty)

    ExtractedStatement(
      bank = if (bank.nonEmpty) bank else "Unknown Bank",
      startDate = startDate,
      endDate = endDate,
      openingBalance = openingBalance,
      closingBalance = closingBalance,
      balances = balances,
      transactions = transactions
    )
  }
}
