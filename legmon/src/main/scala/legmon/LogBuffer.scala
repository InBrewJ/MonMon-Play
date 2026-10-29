package legmon

import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import scala.jdk.CollectionConverters._

case class LogEntry(timestamp: String, level: String, message: String)

object LogBuffer {
  private val maxEntries = 300
  private val buffer = new ConcurrentLinkedDeque[LogEntry]()
  private val errorCount = new AtomicInteger(0)
  private val lastErrorMessage = new AtomicReference[String]("")
  private val hasErrorFlag = new AtomicBoolean(false)

  def log(level: String, message: String): Unit = {
    val entry = LogEntry(Instant.now().toString, level.toUpperCase, message)
    buffer.addLast(entry)
    while (buffer.size() > maxEntries) {
      buffer.pollFirst()
    }

    if (level.equalsIgnoreCase("ERROR") || level.equalsIgnoreCase("WARN")) {
      if (level.equalsIgnoreCase("ERROR")) {
        errorCount.incrementAndGet()
        hasErrorFlag.set(true)
        lastErrorMessage.set(message)
      }
    }
  }

  def info(message: String): Unit = log("INFO", message)
  def warn(message: String): Unit = log("WARN", message)
  def error(message: String): Unit = log("ERROR", message)

  def clearErrorFlag(): Unit = {
    hasErrorFlag.set(false)
  }

  def getRecent(limit: Int = 100): Seq[LogEntry] = {
    val list = buffer.asScala.toSeq
    if (limit <= 0 || limit >= list.size) list else list.takeRight(limit)
  }

  def getStatusSummary: ujson.Obj = {
    ujson.Obj(
      "service" -> "LegMon",
      "status" -> (if (hasErrorFlag.get()) "warning" else "ok"),
      "errorCount" -> errorCount.get(),
      "lastError" -> lastErrorMessage.get(),
      "hasError" -> hasErrorFlag.get(),
      "totalBufferedLogs" -> buffer.size()
    )
  }
}
