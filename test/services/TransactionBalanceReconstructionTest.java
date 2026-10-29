package services;

import helpers.TransactionBalanceReconstructor;
import helpers.TransactionBalanceReconstructor.*;
import models.Balance;
import models.BankTransaction;
import models.PendingStatement;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test suite verifying the mathematical theory and practical implementation of:
 * - Transaction Lists (e.g. TXN_22102026_29072026_29102026_NatWest.pdf) vs Statements
 * - Forward Reconstruction: B(tk) = B(t0) + sum(Delta_i)
 * - Backward Reconstruction: B(t-k) = B(t0) - sum(Delta_-i)
 * - Anchor balance discovery from repository and prompt handling
 */
public class TransactionBalanceReconstructionTest {

    @Test
    public void testNatWestFixtureClassificationAsTransactionList() throws Exception {
        File fixture = new File("legmon/test_fixtures/TXN_22102026_29072026_29102026_NatWest.pdf");
        if (!fixture.exists()) {
            return; // Skip if test fixture not present in checkout
        }

        // Test with pdftotext if available
        try {
            Process p = new ProcessBuilder("pdftotext", fixture.getAbsolutePath(), "-").start();
            String text = new String(p.getInputStream().readAllBytes());
            p.waitFor();

            // Verify properties of transaction list export
            assertThat(text).contains("Transactions-01");
            assertThat(text).contains("From");
            assertThat(text).contains("To");
            assertThat(text).contains("Paid in");
            assertThat(text).contains("Paid out");

            // Notice absence of closing balance or opening balance statements
            assertThat(text.toLowerCase()).doesNotContain("closing balance");
            assertThat(text.toLowerCase()).doesNotContain("opening balance");
        } catch (Exception ignored) {
        }

        // Verify statement classification helper
        PendingStatement txnListStmt = new PendingStatement(
                "user1", "job_123", "NatWest Transactions-01",
                "2026-07-29", "2026-10-22",
                1722211200L, 1729555200L,
                0.0f, 0.0f, 42, "{}"
        );
        assertThat(TransactionBalanceReconstructor.isTransactionListOnly(txnListStmt)).isTrue();

        // Contrasted with a real statement having non-zero closing balance
        PendingStatement realStatement = new PendingStatement(
                "user1", "job_124", "First Direct 1st Account",
                "2026-09-26", "2026-10-26",
                1727308800L, 1729900800L,
                54.10f, 389.50f, 15, "{}"
        );
        assertThat(TransactionBalanceReconstructor.isTransactionListOnly(realStatement)).isFalse();
    }

    @Test
    public void testForwardReconstructionFromStartingAnchor() {
        // Known anchor balance B(t0) = £500.00 at t0 = 1000
        long t0 = 1000L;
        float b0 = 500.00f;

        List<TransactionDelta> deltas = Arrays.asList(
                new TransactionDelta(1100L, 1000.00f, "Tech Salary Inflow"),
                new TransactionDelta(1200L, -200.00f, "Rent Outflow"),
                new TransactionDelta(1300L, -50.00f, "Groceries Outflow")
        );

        ReconstructionResult result = TransactionBalanceReconstructor.reconstruct(
                deltas, t0, b0, 1000L, 1500L
        );

        // Forward: 500.00 + 1000.00 - 200.00 - 50.00 = 1250.00
        assertThat(result.getOpeningBalance()).isEqualTo(500.00f);
        assertThat(result.getClosingBalance()).isEqualTo(1250.00f);
        assertThat(result.getNetDelta()).isEqualTo(750.00f);

        // Check running balances
        assertThat(result.getRunningBalances().get(1100L)).isEqualTo(1500.00f);
        assertThat(result.getRunningBalances().get(1200L)).isEqualTo(1300.00f);
        assertThat(result.getRunningBalances().get(1300L)).isEqualTo(1250.00f);
    }

    @Test
    public void testBackwardReconstructionFromClosingAnchor() {
        // Known anchor balance B(tend) = £1250.00 at tend = 1500
        long tEnd = 1500L;
        float bEnd = 1250.00f;

        List<TransactionDelta> deltas = Arrays.asList(
                new TransactionDelta(1100L, 1000.00f, "Tech Salary Inflow"),
                new TransactionDelta(1200L, -200.00f, "Rent Outflow"),
                new TransactionDelta(1300L, -50.00f, "Groceries Outflow")
        );

        ReconstructionResult result = TransactionBalanceReconstructor.reconstruct(
                deltas, tEnd, bEnd, 1000L, 1500L
        );

        // Backward: 1250.00 - (1000.00 - 200.00 - 50.00) = 500.00
        assertThat(result.getOpeningBalance()).isEqualTo(500.00f);
        assertThat(result.getClosingBalance()).isEqualTo(1250.00f);
    }

    @Test
    public void testBidirectionalMidSequenceReconstruction() {
        // A 30-day transaction list where user provides an anchor balance on Day 15:
        // Day 5: Spend £30.00 (-30)
        // Day 10: Inflow £500.00 (+500)
        // Day 15: ANCHOR BALANCE = £800.00
        // Day 20: Spend £150.00 (-150)
        // Day 28: Spend £50.00 (-50)

        long start = 1000L;
        long day5 = 1005L;
        long day10 = 1010L;
        long day15 = 1015L;
        long day20 = 1020L;
        long day28 = 1028L;
        long end = 1030L;

        List<TransactionDelta> deltas = Arrays.asList(
                new TransactionDelta(day5, -30.00f, "Coffee & Lunch"),
                new TransactionDelta(day10, 500.00f, "Client Invoice Payment"),
                new TransactionDelta(day20, -150.00f, "Electric Utility Bill"),
                new TransactionDelta(day28, -50.00f, "Supermarket Run")
        );

        float anchorBalanceDay15 = 800.00f;

        ReconstructionResult result = TransactionBalanceReconstructor.reconstruct(
                deltas, day15, anchorBalanceDay15, start, end
        );

        // Mathematical verification:
        // Prior to day 15, delta was: -30 + 500 = +470.
        // Therefore, opening balance B(start) = 800 - 470 = 330.00.
        assertThat(result.getOpeningBalance()).isEqualTo(330.00f);

        // After day 15, delta was: -150 - 50 = -200.
        // Therefore, closing balance B(end) = 800 + (-200) = 600.00.
        assertThat(result.getClosingBalance()).isEqualTo(600.00f);

        // Net delta across the entire period: -30 + 500 - 150 - 50 = +270.
        assertThat(result.getNetDelta()).isEqualTo(270.00f);
        // And B(end) - B(start) = 600 - 330 = 270.00!
        assertThat(result.getClosingBalance() - result.getOpeningBalance()).isEqualTo(270.00f);
    }

    @Test
    public void testFromBankTransactionsConversion() {
        // MonMon convention:
        // In BankTransaction, an expense is positive amount (e.g. 25.50 for lunch).
        // In delta mathematics, an expense is negative delta (-25.50).
        BankTransaction spend = new BankTransaction();
        spend.setAmount(25.50f);
        spend.setTimestamp(100L);
        spend.setDescription("Lunch");

        BankTransaction income = new BankTransaction();
        income.setAmount(-200.00f); // MonMon negative amount = income
        income.setTimestamp(200L);
        income.setDescription("Refund");

        List<TransactionDelta> deltas = TransactionBalanceReconstructor.fromBankTransactions(Arrays.asList(spend, income));
        assertThat(deltas).hasSize(2);
        assertThat(deltas.get(0).getDelta()).isEqualTo(-25.50f);
        assertThat(deltas.get(1).getDelta()).isEqualTo(200.00f);
    }

    @Test
    public void testAutoDiscoveryOfExistingAnchorBalance() {
        long start = 1722211200L; // 2026-07-29
        long end = 1729555200L;   // 2026-10-22

        Balance outsideBefore = new Balance();
        outsideBefore.setTimestamp(start - 1000);
        outsideBefore.setValue(100.0f);

        Balance insideMiddle = new Balance();
        insideMiddle.setTimestamp(start + 50000);
        insideMiddle.setValue(845.20f);

        Balance outsideAfter = new Balance();
        outsideAfter.setTimestamp(end + 1000);
        outsideAfter.setValue(900.0f);

        List<Balance> balances = Arrays.asList(outsideBefore, insideMiddle, outsideAfter);

        Optional<Balance> anchor = TransactionBalanceReconstructor.findAnchorBalance(balances, start, end);
        assertThat(anchor).isPresent();
        assertThat(anchor.get().getValue()).isEqualTo(845.20f);
    }

    @Test
    public void testSyntheticStatementFixturesReadableByPdfToText() throws Exception {
        String[] samples = new String[]{
                "legmon/test_fixtures/sample_monzo_statement.pdf",
                "legmon/test_fixtures/sample_natwest_statement.pdf",
                "legmon/test_fixtures/sample_firstdirect_statement.pdf",
                "legmon/test_fixtures/sample_natwest_transaction_list.pdf"
        };

        for (String samplePath : samples) {
            File f = new File(samplePath);
            assertThat(f).exists();
            assertThat(f.length()).isGreaterThan(200);

            Process p = new ProcessBuilder("pdftotext", f.getAbsolutePath(), "-").start();
            String text = new String(p.getInputStream().readAllBytes());
            p.waitFor();

            assertThat(text.length()).isGreaterThan(50);
            boolean matchedBank = text.contains("Monzo") || text.contains("NatWest") || text.contains("first direct") || text.contains("Transactions-01");
            assertThat(matchedBank).isTrue();
        }
    }

    @Test
    public void testSyntheticTransactionListFixtureHasNoBalances() throws Exception {
        File f = new File("legmon/test_fixtures/sample_natwest_transaction_list.pdf");
        assertThat(f).exists();

        Process p = new ProcessBuilder("pdftotext", f.getAbsolutePath(), "-").start();
        String text = new String(p.getInputStream().readAllBytes());
        p.waitFor();

        // Must contain transaction columns
        assertThat(text).contains("Transactions-01");
        assertThat(text).contains("Paid in");
        assertThat(text).contains("Paid out");

        // Must NOT contain balance columns or summary
        assertThat(text.toLowerCase()).doesNotContain("closing balance");
        assertThat(text.toLowerCase()).doesNotContain("opening balance");
    }
}
