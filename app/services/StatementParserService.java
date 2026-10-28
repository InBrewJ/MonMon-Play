package services;

import models.BankTransaction;
import models.BankTransactionRepository;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@Singleton
public class StatementParserService {

    private final BankTransactionRepository bankTransactionRepository;

    private static final List<DateTimeFormatter> DATE_FORMATTERS = Arrays.asList(
            DateTimeFormatter.ISO_LOCAL_DATE,                           // 2026-10-28
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),                  // 28/10/2026
            DateTimeFormatter.ofPattern("d/M/yyyy"),                    // 8/10/2026
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),                  // 28-10-2026
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),                  // 2026/10/28
            DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH), // 28 Oct 2026
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)   // 8 Oct 2026
    );

    @Inject
    public StatementParserService(BankTransactionRepository bankTransactionRepository) {
        this.bankTransactionRepository = bankTransactionRepository;
    }

    public List<BankTransaction> parseCsv(String userId, Long accountId, String csvContent) {
        if (csvContent == null || csvContent.trim().isEmpty()) {
            return Collections.emptyList();
        }

        String[] lines = csvContent.split("\\r?\\n");
        if (lines.length <= 1) {
            return Collections.emptyList();
        }

        List<String> headers = parseCsvLine(lines[0]);
        int dateCol = -1;
        int descCol = -1;
        int amountCol = -1;
        int paidOutCol = -1;
        int paidInCol = -1;
        int categoryCol = -1;
        int merchantCol = -1;

        for (int i = 0; i < headers.size(); i++) {
            String h = headers.get(i).trim().toLowerCase();
            if (dateCol == -1 && (h.contains("date") || h.equals("posted") || h.equals("time"))) {
                dateCol = i;
            } else if (descCol == -1 && (h.contains("desc") || h.contains("narrative") || h.contains("payee") || h.contains("memo") || h.contains("details"))) {
                descCol = i;
            } else if (h.contains("paid out") || h.contains("debit") || h.contains("money out")) {
                paidOutCol = i;
            } else if (h.contains("paid in") || h.contains("credit") || h.contains("money in")) {
                paidInCol = i;
            } else if (amountCol == -1 && (h.contains("amount") || h.contains("value") || h.contains("total"))) {
                amountCol = i;
            } else if (categoryCol == -1 && h.contains("category")) {
                categoryCol = i;
            } else if (merchantCol == -1 && (h.contains("merchant") || h.contains("retailer"))) {
                merchantCol = i;
            }
        }

        List<BankTransaction> results = new ArrayList<>();

        for (int lineIdx = 1; lineIdx < lines.length; lineIdx++) {
            String line = lines[lineIdx].trim();
            if (line.isEmpty()) {
                continue;
            }
            List<String> cols = parseCsvLine(line);
            if (cols.isEmpty()) {
                continue;
            }

            String dateStr = (dateCol >= 0 && dateCol < cols.size()) ? cols.get(dateCol).trim() : null;
            String desc = (descCol >= 0 && descCol < cols.size()) ? cols.get(descCol).trim() : "Transaction";
            String category = (categoryCol >= 0 && categoryCol < cols.size()) ? cols.get(categoryCol).trim() : "General";
            String merchant = (merchantCol >= 0 && merchantCol < cols.size()) ? cols.get(merchantCol).trim() : null;

            Float amount = null;
            if (paidOutCol >= 0 && paidOutCol < cols.size() && !cols.get(paidOutCol).trim().isEmpty()) {
                Float out = cleanAmount(cols.get(paidOutCol));
                if (out != null && out > 0) {
                    amount = Math.abs(out);
                }
            }
            if (amount == null && paidInCol >= 0 && paidInCol < cols.size() && !cols.get(paidInCol).trim().isEmpty()) {
                Float in = cleanAmount(cols.get(paidInCol));
                if (in != null && in > 0) {
                    amount = -Math.abs(in); // credit / income is negative spend
                }
            }
            if (amount == null && amountCol >= 0 && amountCol < cols.size()) {
                Float raw = cleanAmount(cols.get(amountCol));
                if (raw != null) {
                    // In bank exports, negative amounts are debits/outflows
                    // In MonMon spend tracking, positive represents money spent
                    if (raw < 0) {
                        amount = Math.abs(raw);
                    } else {
                        // Could be outflow or inflow, if positive and labeled debit vs credit
                        amount = raw;
                    }
                }
            }

            if (amount == null) {
                amount = 0.0f;
            }

            Long timestamp = parseDateToEpochSec(dateStr);
            String extId = "csv-" + accountId + "-" + hashString(dateStr + "|" + amount + "|" + desc + "|" + lineIdx);

            BankTransaction tx = new BankTransaction(
                    userId,
                    accountId,
                    extId,
                    timestamp,
                    dateStr,
                    amount,
                    desc,
                    merchant,
                    category,
                    "FILE_UPLOAD",
                    false
            );
            results.add(tx);
        }

        return results;
    }

    public CompletionStage<BankSyncService.SyncResult> importStatement(
            String userId, Long accountId, String csvContent, boolean rewriteHistory) {

        List<BankTransaction> txList = parseCsv(userId, accountId, csvContent);
        if (txList.isEmpty()) {
            return CompletableFuture.completedFuture(
                    BankSyncService.SyncResult.error("No valid transactions found in statement."));
        }

        if (rewriteHistory) {
            // Dangerously rewrite history: purge existing records in range and replace
            long minTime = Long.MAX_VALUE;
            long maxTime = Long.MIN_VALUE;
            for (BankTransaction t : txList) {
                if (t.getTimestamp() != null) {
                    if (t.getTimestamp() < minTime) minTime = t.getTimestamp();
                    if (t.getTimestamp() > maxTime) maxTime = t.getTimestamp();
                }
            }
            if (minTime == Long.MAX_VALUE) {
                minTime = 0L;
                maxTime = System.currentTimeMillis() / 1000L;
            }
            final long fMin = minTime;
            final long fMax = maxTime + 86400L; // encompass whole day

            return bankTransactionRepository.deleteByTimeRange(accountId, fMin, fMax)
                    .thenCompose(deleted -> bankTransactionRepository.saveAll(txList))
                    .thenApply(saved -> BankSyncService.SyncResult.ok(
                            saved,
                            "Dangerously rewrote history: Replaced existing transactions with " + saved + " transactions from statement."));
        } else {
            return bankTransactionRepository.saveAll(txList)
                    .thenApply(saved -> BankSyncService.SyncResult.ok(
                            saved,
                            "Successfully imported " + saved + " transactions from statement."));
        }
    }

    private Float cleanAmount(String s) {
        if (s == null) return null;
        try {
            String cleaned = s.replaceAll("[^0-9.\\-]", "");
            if (cleaned.isEmpty()) return null;
            return Float.parseFloat(cleaned);
        } catch (Exception e) {
            return null;
        }
    }

    private Long parseDateToEpochSec(String dateStr) {
        if (dateStr == null || dateStr.trim().isEmpty()) {
            return System.currentTimeMillis() / 1000L;
        }
        for (DateTimeFormatter fmt : DATE_FORMATTERS) {
            try {
                LocalDate d = LocalDate.parse(dateStr.trim(), fmt);
                return d.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
            } catch (Exception ignored) {
            }
        }
        return System.currentTimeMillis() / 1000L;
    }

    private static List<String> parseCsvLine(String line) {
        List<String> result = new ArrayList<>();
        if (line == null) return result;
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                result.add(sb.toString().trim());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        result.add(sb.toString().trim());
        return result;
    }

    private static String hashString(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.substring(0, 16);
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
