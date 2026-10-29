package legmon

import org.slf4j.LoggerFactory
import scala.util.{Failure, Success, Try}

object MonzoWebhookSender {
  private val logger = LoggerFactory.getLogger(getClass)

  def sendToWebhook(
      userId: String,
      jobId: String,
      statement: ExtractedStatement,
      targetAccountId: Option[Long],
      callbackUrl: String
  ): Boolean = {
    logger.info(s"Sending extracted statement for user '$userId' (bank='${statement.bank}', ${statement.transactions.size} txs) to webhook: $callbackUrl")
    LogBuffer.info(s"Dispatching Monzo-compatible statement webhook to MonMon ($callbackUrl)")

    // Convert transactions to Monzo webhook-compatible JSON
    val monzoTransactions = statement.transactions.map { tx =>
      // Monzo webhook format: amount is in integer pence; negative for spend, positive for income
      val monzoPence = -Math.round(tx.amount * 100)
      val createdIso = if (tx.date.contains("T")) tx.date else s"${tx.date}T12:00:00Z"

      ujson.Obj(
        "type" -> "transaction.created",
        "data" -> ujson.Obj(
          "id" -> tx.id,
          "account_id" -> ujson.Str(targetAccountId.map(_.toString).getOrElse("")),
          "amount" -> monzoPence,
          "created" -> createdIso,
          "currency" -> "GBP",
          "description" -> tx.description,
          "category" -> tx.category,
          "settled" -> createdIso,
          "merchant" -> ujson.Obj(
            "name" -> tx.merchant,
            "category" -> tx.category
          )
        )
      )
    }

    val balancesArr = statement.balances.map { b =>
      ujson.Obj(
        "date" -> b.date,
        "value" -> b.value
      )
    }

    val webhookPayload = ujson.Obj(
      "type" -> "statement.extracted",
      "userId" -> userId,
      "jobId" -> jobId,
      "bank" -> statement.bank,
      "startDate" -> statement.startDate,
      "endDate" -> statement.endDate,
      "openingBalance" -> statement.openingBalance,
      "closingBalance" -> statement.closingBalance,
      "targetAccountId" -> targetAccountId.map(id => ujson.Num(id.toDouble)).getOrElse(ujson.Null),
      "balances" -> ujson.Arr(balancesArr: _*),
      "transactions" -> ujson.Arr(monzoTransactions: _*)
    )

    var success = false
    var attempt = 1
    val maxAttempts = 3

    while (attempt <= maxAttempts && !success) {
      Try {
        val res = requests.post(
          callbackUrl,
          headers = Seq("Content-Type" -> "application/json"),
          data = ujson.write(webhookPayload),
          connectTimeout = 10000,
          readTimeout = 20000
        )
        if (res.statusCode >= 200 && res.statusCode < 300) {
          logger.info(s"Webhook successfully dispatched to MonMon! HTTP ${res.statusCode}")
          LogBuffer.info(s"Statement webhook successfully delivered to MonMon (HTTP ${res.statusCode})")
          success = true
        } else {
          throw new RuntimeException(s"Webhook returned HTTP ${res.statusCode}: ${res.text()}")
        }
      } match {
        case Success(_) => // done
        case Failure(ex) =>
          val msg = s"Webhook delivery attempt $attempt/$maxAttempts failed: ${ex.getMessage}"
          logger.warn(msg)
          LogBuffer.warn(msg)
          if (attempt < maxAttempts) Thread.sleep(1500)
          attempt += 1
      }
    }

    if (!success) {
      val err = s"Failed to deliver statement webhook to $callbackUrl after $maxAttempts attempts"
      logger.error(err)
      LogBuffer.error(err)
    }

    success
  }
}
