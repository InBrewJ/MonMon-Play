package legmon

import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import scala.collection.mutable.ArrayBuffer
import scala.util.Try
import scala.util.matching.Regex

/**
 * Fixed-cost, zero-LLM deterministic statement & transaction list parser.
 *
 * Operates on `pdftotext -layout` (or `tesseract --psm 6 -c preserve_interword_spaces=1` / Mistral OCR markdown)
 * using:
 *   1. Markdown pipe-table parsing (when pipe tables are present)
 *   2. Explicit signed-amount row parsing (NatWest `-£` / `£`, Monzo `-GBP` / `+GBP`)
 *   3. Horizontal column-band X-index matching against the active table header (`Paid in` / `Money in` vs `Paid out` / `Money out` vs `Balance`)
 */
object DeterministicStatementParser {
  private val logger = LoggerFactory.getLogger(getClass)

  private val MonthMap: Map[String, Int] = Map(
    "jan" -> 1, "january" -> 1,
    "feb" -> 2, "february" -> 2,
    "mar" -> 3, "march" -> 3,
    "apr" -> 4, "april" -> 4,
    "may" -> 5,
    "jun" -> 6, "june" -> 6,
    "jul" -> 7, "july" -> 7,
    "aug" -> 8, "august" -> 8,
    "sep" -> 9, "sept" -> 9, "september" -> 9,
    "oct" -> 10, "october" -> 10,
    "nov" -> 11, "november" -> 11,
    "dec" -> 12, "december" -> 12
  )

  // NatWest signed row: "22 Oct   ACC-NWESTMSTR   Mobile/Online Transaction   -£33.00" or "£3,111.87"
  private val NatWestSignedRow: Regex =
    """^\s*(\d{2}\s+[A-Za-z]{3})\s{2,}(.+?)\s{2,}(.+?)\s{2,}(-?[£E][\d,]+\.\d{2})\s*$""".r

  // Markdown pipe row: "| 22 Oct | ACC-NWESTMSTR | Mobile/Online Transaction | | -£33.00 |"
  private val MarkdownTableRow: Regex =
    """^\s*\|\s*(\d{1,2}\s+[A-Za-z]{3}(?:\s+\d{2,4})?)\s*\|\s*([^|]+?)\s*\|\s*([^|]*?)\s*\|\s*([^|]*?)\s*\|\s*([^|]*?)\s*\|.*$""".r

  // Leading date at start of a layout line: "22 Oct 2026", "22 Oct 26", "22 Oct", "22/10/2026"
  private val LeadingDatePattern: Regex =
    """^\s*(\d{1,2}\s+[A-Za-z]{3,9}(?:\s+\d{2,4})?|\d{2}/\d{2}/\d{4})\b(.*)$""".r

  // Currency/decimal token with its start index in the line (excludes percentages like 39.90%)
  private val AmountTokenPattern: Regex =
    """([+\-]?)\s*(?:GBP|[£E])?\s*([\d,]+\.\d{2})(?![0-9%])""".r

  private case class ColumnBands(
      paidInCol: Option[Int],
      paidOutCol: Option[Int],
      balanceCol: Option[Int],
      amountCol: Option[Int]
  )

  def parse(rawText: String): ExtractedStatement = {
    val bank = detectBank(rawText)
    val defaultYear = detectDefaultYear(rawText)
    val (headerStart, headerEnd) = detectPeriodDates(rawText, defaultYear)
    val (openBalOpt, closeBalOpt) = detectBalances(rawText)

    // 1. Try Markdown pipe-table extraction first if pipe tables exist
    val mdTxs = parseMarkdownTables(rawText, defaultYear)
    val transactions = if (mdTxs.nonEmpty) {
      mdTxs
    } else {
      // 2. Try NatWest signed export fast-path if applicable
      val nwTxs = parseNatWestSignedRows(rawText, defaultYear)
      if (nwTxs.nonEmpty) nwTxs
      else parseLayoutColumns(rawText, defaultYear)
    }

    val dates = transactions.map(_.date).filter(_.matches("""\d{4}-\d{2}-\d{2}"""))
    val startDate = headerStart.orElse(dates.minOption).getOrElse(LocalDate.now().minusDays(30).toString)
    val endDate = headerEnd.orElse(dates.maxOption).getOrElse(LocalDate.now().toString)

    val openingBalance = openBalOpt.getOrElse(0.0f)
    val closingBalance = closeBalOpt.getOrElse(0.0f)

    val paidInBd = transactions.foldLeft(BigDecimal(0)) { (acc, tx) =>
      val d = BigDecimal(tx.amount.toString).setScale(2, BigDecimal.RoundingMode.HALF_UP)
      if (d < 0) acc + (-d) else acc
    }.setScale(2, BigDecimal.RoundingMode.HALF_UP)

    val paidOutBd = transactions.foldLeft(BigDecimal(0)) { (acc, tx) =>
      val d = BigDecimal(tx.amount.toString).setScale(2, BigDecimal.RoundingMode.HALF_UP)
      if (d > 0) acc + d else acc
    }.setScale(2, BigDecimal.RoundingMode.HALF_UP)

    logger.info(s"DeterministicStatementParser extracted bank='$bank', period=$startDate..$endDate, txs=${transactions.size}, paidIn=£$paidInBd, paidOut=£$paidOutBd")
    LogBuffer.info(s"Deterministic fallback extracted Bank='$bank', ${transactions.size} transactions (Paid In: £$paidInBd, Paid Out: £$paidOutBd)")

    ExtractedStatement(
      bank = bank,
      startDate = startDate,
      endDate = endDate,
      openingBalance = openingBalance,
      closingBalance = closingBalance,
      balances = if (closingBalance != 0.0f) Seq(ExtractedBalance(endDate, closingBalance)) else Seq.empty,
      transactions = transactions,
      totalPaidIn = paidInBd.toFloat,
      totalPaidOut = paidOutBd.toFloat
    )
  }

  private def parseMarkdownTables(text: String, defaultYear: Int): Seq[ExtractedTransaction] = {
    val buf = ArrayBuffer.empty[ExtractedTransaction]
    var idx = 0
    for (line <- text.linesIterator) {
      line match {
        case MarkdownTableRow(dateRaw, descRaw, typeRaw, paidInRaw, paidOutRaw) =>
          val paidInVal = parseUnsignedBigDecimal(paidInRaw)
          val paidOutVal = parseUnsignedBigDecimal(paidOutRaw)
          if (paidInVal.isDefined || paidOutVal.isDefined) {
            val isoDate = normalizeDate(dateRaw.trim, defaultYear)
            val desc = descRaw.trim
            val signedAmount: BigDecimal = paidOutVal match {
              case Some(out) if out > 0 => out
              case _ => -paidInVal.getOrElse(BigDecimal(0))
            }
            buf += buildTx(idx, isoDate, desc, typeRaw.trim, signedAmount)
            idx += 1
          }
        case _ =>
      }
    }
    buf.toSeq
  }

  private def parseNatWestSignedRows(text: String, defaultYear: Int): Seq[ExtractedTransaction] = {
    val buf = ArrayBuffer.empty[ExtractedTransaction]
    var idx = 0
    for (line <- text.linesIterator) {
      line match {
        case NatWestSignedRow(dateRaw, descRaw, typeRaw, amtRaw) =>
          val clean = amtRaw.replace("£", "").replace("E", "").replace(",", "").trim
          Try(BigDecimal(clean)).toOption.foreach { rawVal =>
            // In NatWest signed exports: -£33.00 is Paid Out (+33.00 in MonMon); £3,111.87 is Paid In (-3111.87 in MonMon)
            val monmonAmount = (-rawVal).setScale(2, BigDecimal.RoundingMode.HALF_UP)
            val isoDate = normalizeDate(dateRaw.trim, defaultYear)
            buf += buildTx(idx, isoDate, descRaw.trim, typeRaw.trim, monmonAmount)
            idx += 1
          }
        case _ =>
      }
    }
    buf.toSeq
  }

  private def parseLayoutColumns(text: String, defaultYear: Int): Seq[ExtractedTransaction] = {
    val buf = ArrayBuffer.empty[ExtractedTransaction]
    var bands = ColumnBands(None, None, None, None)
    var currentDate: Option[String] = None
    var pendingDesc = new StringBuilder
    var idx = 0

    for (line <- text.linesIterator) {
      val lower = line.toLowerCase(Locale.ROOT)
      val compactLower = lower.replace(" ", "")

      // Stop table continuation state when hitting footer/legal sections
      if (lower.contains("information about the financial services compensation scheme") ||
          lower.startsWith("transaction types") ||
          lower.contains("about your statement:")) {
        currentDate = None
        pendingDesc.clear()
      } else if ((lower.contains("date") || compactLower.contains("paymenttype")) &&
          (lower.contains("paid in") || lower.contains("paid out") || lower.contains("money in") || lower.contains("money out") || lower.contains("amount"))) {
        val pIn = indexOfAny(lower, Seq("paid in", "money in", "credit", "deposits"))
        val pOut = indexOfAny(lower, Seq("paid out", "money out", "debit", "withdrawals"))
        val bal = indexOfAny(lower, Seq("balance"))
        val amt = indexOfAny(lower, Seq("amount"))
        bands = ColumnBands(pIn, pOut, bal, amt)
        pendingDesc.clear()
      } else if (!isNoiseLine(lower)) {
        line match {
          case LeadingDatePattern(dateToken, rest) if isValidDateToken(dateToken) =>
            currentDate = Some(normalizeDate(dateToken.trim, defaultYear))
            pendingDesc.clear()
            extractRowFromLine(line, dateToken, rest, currentDate.get, bands, pendingDesc) match {
              case Some((desc, txType, signedAmt)) =>
                buf += buildTx(idx, currentDate.get, desc, txType, signedAmt)
                idx += 1
                pendingDesc.clear()
              case None =>
                val textOnly = rest.trim
                if (textOnly.nonEmpty) pendingDesc.append(textOnly).append(" ")
            }

          case _ if currentDate.isDefined && line.trim.nonEmpty =>
            // Continuation line (e.g. First Direct multi-line transaction or same-date subsequent item)
            extractRowFromLine(line, "", line, currentDate.get, bands, pendingDesc) match {
              case Some((desc, txType, signedAmt)) =>
                buf += buildTx(idx, currentDate.get, desc, txType, signedAmt)
                idx += 1
                pendingDesc.clear()
              case None =>
                val trimmed = line.trim
                if (trimmed.nonEmpty && !trimmed.equalsIgnoreCase("blank.")) {
                  pendingDesc.append(trimmed).append(" ")
                }
            }

          case _ =>
        }
      }
    }

    buf.toSeq
  }

  private def extractRowFromLine(
      fullLine: String,
      dateToken: String,
      restOfLine: String,
      isoDate: String,
      bands: ColumnBands,
      pendingDesc: StringBuilder
  ): Option[(String, String, BigDecimal)] = {
    val dateEndIdx = if (dateToken.nonEmpty) fullLine.indexOf(dateToken) + dateToken.length else 0
    val matches = AmountTokenPattern.findAllMatchIn(fullLine).filter(_.start >= dateEndIdx).toSeq
    if (matches.isEmpty) return None

    val firstMatchStart = matches.head.start
    val descPart = fullLine.substring(dateEndIdx, firstMatchStart).trim
    val combinedDesc = (pendingDesc.toString().trim + " " + descPart).trim.replaceAll("""\s{2,}""", " ")
    val compactDesc = combinedDesc.toLowerCase(Locale.ROOT).replace(" ", "")
    if (compactDesc.contains("balancebroughtforward") ||
        compactDesc.contains("balancecarriedforward") ||
        combinedDesc.isEmpty) {
      pendingDesc.clear()
      return None
    }

    // Filter out the trailing Balance column token if a Balance column exists and there are >= 2 numbers (or if the only number is at the Balance column)
    val nonBalanceMatches = matches.filterNot { m =>
      bands.balanceCol.exists { bCol =>
        val distToBal = Math.abs(m.start - bCol)
        val distToIn = bands.paidInCol.map(c => Math.abs(m.start - c)).getOrElse(999)
        val distToOut = bands.paidOutCol.map(c => Math.abs(m.start - c)).getOrElse(999)
        val distToAmt = bands.amountCol.map(c => Math.abs(m.start - c)).getOrElse(999)
        distToBal < distToIn && distToBal < distToOut && distToAmt > distToBal && (matches.size >= 2 || distToBal <= 12)
      }
    }

    if (nonBalanceMatches.isEmpty) return None
    val txMatch = nonBalanceMatches.head
    val signStr = txMatch.group(1)
    val mag = BigDecimal(txMatch.group(2).replace(",", "")).setScale(2, BigDecimal.RoundingMode.HALF_UP)
    if (mag == BigDecimal(0)) return None

    // Determine whether this transaction is Paid In (negative in MonMon) or Paid Out (positive in MonMon)
    val isPaidIn: Boolean = if (signStr == "+") {
      true
    } else if (signStr == "-") {
      false
    } else if (bands.paidInCol.isDefined && bands.paidOutCol.isDefined) {
      val inCol = bands.paidInCol.get
      val outCol = bands.paidOutCol.get
      // Compare distance from token's center to column headers
      val tokenCenter = (txMatch.start + txMatch.end) / 2
      val inCenter = inCol + 4
      val outCenter = outCol + 4
      Math.abs(tokenCenter - inCenter) < Math.abs(tokenCenter - outCenter)
    } else if (bands.paidInCol.isDefined && bands.paidOutCol.isEmpty) {
      true
    } else if (bands.paidOutCol.isDefined && bands.paidInCol.isEmpty) {
      false
    } else {
      val upper = combinedDesc.toUpperCase(Locale.ROOT)
      upper.startsWith("CR ") || upper.contains(" BGC") || upper.contains(" F/P") || upper.contains("AUTOMATED CREDIT") || upper.contains("PAYROLL")
    }

    val signedAmount = if (isPaidIn) -mag else mag
    Some((combinedDesc, "", signedAmount))
  }

  private def buildTx(idx: Int, isoDate: String, rawDesc: String, rawType: String, signedAmount: BigDecimal): ExtractedTransaction = {
    val cleanDesc = rawDesc.replaceAll("""\s+""", " ").trim
    val category = classifyCategory(cleanDesc, rawType, signedAmount)
    ExtractedTransaction(
      id = s"legmon_det_tx_${System.currentTimeMillis()}_$idx",
      date = isoDate,
      description = cleanDesc,
      amount = signedAmount.toFloat,
      merchant = cleanDesc,
      category = category
    )
  }

  private def classifyCategory(desc: String, txType: String, amount: BigDecimal): String = {
    if (amount < 0) return "general"
    val u = (desc + " " + txType).toLowerCase(Locale.ROOT)
    if (Seq("sainsbury", "tesco", "morrisons", "asda", "waitrose", "aldi", "lidl", "coop", "co-op", "marks & spencer", "m&s", "mini market", "food and wine").exists(u.contains))
      "groceries"
    else if (Seq("pret", "kfc", "greggs", "subway", "costa", "starbucks", "dishoom", "abbey arms", "cookies", "cafe", "coffee", "restaurant").exists(u.contains))
      "dining"
    else if (Seq("tfl", "transport for london", "trainline", "scotrail", "uber", "roadchef", "welcome break", "bp ", "shell ", "fuel", "dvla").exists(u.contains))
      "transport"
    else if (Seq("council tax", "scottishpower", "octopus", "scottish water", "aviva", "tv licence", "o2", "h3g", "vodafone", "halifax", "natwest", "charges", "interest", "direct debit").exists(u.contains))
      "bills"
    else if (Seq("netflix", "spotify", "cinema", "caravan club").exists(u.contains))
      "entertainment"
    else if (Seq("amazon", "apple", "whsmith", "sumup", "lock", "claude", "remarkable", "google").exists(u.contains))
      "shopping"
    else
      "general"
  }

  private def detectBank(text: String): String = {
    val lower = text.toLowerCase(Locale.ROOT)
    if (lower.contains("natwest") || lower.contains("national westminster")) "NatWest"
    else if (lower.contains("first direct") || lower.contains("firstdirect")) "First Direct"
    else if (lower.contains("monzo")) "Monzo"
    else if (lower.contains("lloyds")) "Lloyds Bank"
    else if (lower.contains("barclays")) "Barclays"
    else if (lower.contains("hsbc")) "HSBC"
    else if (lower.contains("santander")) "Santander"
    else if (lower.contains("nationwide")) "Nationwide"
    else if (lower.contains("american express") || lower.contains("amex")) "American Express"
    else if (lower.contains("chase")) "Chase"
    else "Unknown Bank"
  }

  private def detectDefaultYear(text: String): Int = {
    val yearRegex = """\b(202\d)\b""".r
    val years = yearRegex.findAllMatchIn(text).map(_.group(1).toInt).toSeq
    if (years.nonEmpty) years.groupBy(identity).maxBy(_._2.size)._1
    else LocalDate.now().getYear
  }

  private def detectPeriodDates(text: String, defaultYear: Int): (Option[String], Option[String]) = {
    // 1. NatWest From / To: "From 29/07/2026 To 22/10/2026" (possibly across lines)
    val slashDates = """\b(\d{2}/\d{2}/\d{4})\b""".r.findAllMatchIn(text.take(1200)).map(_.group(1)).toSeq
    if (slashDates.size >= 2) {
      return (Some(normalizeDate(slashDates(0), defaultYear)), Some(normalizeDate(slashDates(1), defaultYear)))
    }
    // 2. "27 September to 26 October 2026" or "01 Oct 2026 to 28 Oct 2026"
    val rangeRegex = """(?i)\b(\d{1,2}\s+[A-Za-z]{3,9}(?:\s+\d{4})?)\s+to\s+(\d{1,2}\s+[A-Za-z]{3,9}\s+\d{4})\b""".r
    rangeRegex.findFirstMatchIn(text.take(1500)).foreach { m =>
      val endIso = normalizeDate(m.group(2), defaultYear)
      val endYear = Try(endIso.take(4).toInt).getOrElse(defaultYear)
      val startIso = normalizeDate(m.group(1), endYear)
      return (Some(startIso), Some(endIso))
    }
    (None, None)
  }

  private def detectBalances(text: String): (Option[Float], Option[Float]) = {
    val head = text.take(2500)
    val explicitOpen = """(?i)(?:opening balance|starting balance|balance from previous statement)\s*[:.]?\s*(?:GBP|£)?\s*(-?[\d,]+\.\d{2})""".r
      .findFirstMatchIn(head)
      .flatMap(m => Try(m.group(1).replace(",", "").toFloat).toOption)
    val explicitClose = """(?i)closing balance\s*[:.]?\s*(?:GBP|£)?\s*(-?[\d,]+\.\d{2})""".r
      .findFirstMatchIn(head)
      .flatMap(m => Try(m.group(1).replace(",", "").toFloat).toOption)

    val balanceOnMatches = """(?i)balance on \d{1,2} [A-Za-z]+ \d{4}\s*[.:]*\s*(?:GBP|£)?\s*(-?[\d,]+\.\d{2})""".r
      .findAllMatchIn(head)
      .flatMap(m => Try(m.group(1).replace(",", "").toFloat).toOption)
      .toSeq

    val openOpt = explicitOpen.orElse(if (balanceOnMatches.size >= 2) balanceOnMatches.headOption else None)
    val closeOpt = explicitClose.orElse(balanceOnMatches.lastOption)
    (openOpt, closeOpt)
  }

  private def normalizeDate(raw: String, defaultYear: Int): String = {
    val clean = raw.trim.replaceAll("""\s+""", " ")
    if (clean.matches("""\d{2}/\d{2}/\d{4}""")) {
      val p = clean.split("/")
      return f"${p(2).toInt}%04d-${p(1).toInt}%02d-${p(0).toInt}%02d"
    }
    val parts = clean.split(" ")
    if (parts.length >= 2) {
      val day = Try(parts(0).toInt).getOrElse(1)
      val month = MonthMap.getOrElse(parts(1).toLowerCase(Locale.ROOT).take(3), 1)
      val year = if (parts.length >= 3) {
        val y = Try(parts(2).toInt).getOrElse(defaultYear)
        if (y < 100) 2000 + y else y
      } else defaultYear
      f"$year%04d-$month%02d-$day%02d"
    } else LocalDate.now().toString
  }

  private def isValidDateToken(token: String): Boolean = {
    val t = token.trim
    if (t.matches("""\d{2}/\d{2}/\d{4}""")) return true
    val parts = t.split("""\s+""")
    parts.length >= 2 && Try(parts(0).toInt).toOption.exists(d => d >= 1 && d <= 31) &&
      MonthMap.contains(parts(1).toLowerCase(Locale.ROOT).take(3))
  }

  private def parseUnsignedBigDecimal(s: String): Option[BigDecimal] = {
    val cleaned = s.replaceAll("""[^0-9.]""", "").trim
    if (cleaned.nonEmpty) Try(BigDecimal(cleaned).setScale(2, BigDecimal.RoundingMode.HALF_UP)).toOption else None
  }

  private def indexOfAny(lowerLine: String, needles: Seq[String]): Option[Int] = {
    needles.view.map(n => lowerLine.indexOf(n)).find(_ >= 0)
  }

  private def isNoiseLine(lower: String): Boolean = {
    val t = lower.trim
    t.isEmpty ||
      t.startsWith("page ") ||
      t.startsWith("transactions-0") ||
      t.startsWith("your transactions") ||
      t.contains("prudential regulation authority") ||
      t.contains("national westminster bank plc") ||
      t.contains("sort code") ||
      t.contains("account number")
  }
}
