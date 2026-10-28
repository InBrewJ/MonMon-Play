package services;

import models.Account;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletionStage;

public interface BankSyncService {

    class SyncResult {
        private final boolean success;
        private final int importedCount;
        private final String message;
        private final List<String> details;

        public SyncResult(boolean success, int importedCount, String message, List<String> details) {
            this.success = success;
            this.importedCount = importedCount;
            this.message = message;
            this.details = details != null ? details : Collections.emptyList();
        }

        public static SyncResult ok(int count, String message) {
            return new SyncResult(true, count, message, Collections.emptyList());
        }

        public static SyncResult ok(int count, String message, List<String> details) {
            return new SyncResult(true, count, message, details);
        }

        public static SyncResult error(String message) {
            return new SyncResult(false, 0, message, Collections.emptyList());
        }

        public boolean isSuccess() {
            return success;
        }

        public int getImportedCount() {
            return importedCount;
        }

        public String getMessage() {
            return message;
        }

        public List<String> getDetails() {
            return details;
        }
    }

    CompletionStage<SyncResult> syncAccount(Account account, int daysBack);

    CompletionStage<SyncResult> syncAllUserAccounts(String userId, List<Account> accounts, int daysBack);

    CompletionStage<SyncResult> importStatement(String userId, Long accountId, String statementData, boolean rewriteHistory);

    String getProviderName();
}
