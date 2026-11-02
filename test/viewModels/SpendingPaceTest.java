package viewModels;

import helpers.SpendingPaceCalculator;
import models.Account;
import models.Balance;
import models.Incoming;
import models.Outgoing;
import models.Plan;
import models.Pot;
import org.junit.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class SpendingPaceTest {

    private Account createAccountWithBalances(long id, String name, List<Balance> balances) {
        Account acc = new Account();
        acc.setId(id);
        acc.setName(name);
        acc.setType(Account.AccountType.DEBIT);
        acc.balances = balances;
        acc.outgoings = Collections.emptyList();
        return acc;
    }

    private Balance createBalance(Account acc, float value, long epochSec) {
        Balance b = new Balance();
        b.setAccount(acc);
        b.setValue(value);
        b.setTimestamp(epochSec);
        return b;
    }

    @Test
    public void testSpendingPaceChunkCalculations() {
        LocalDate start = LocalDate.of(2026, 10, 26);
        LocalDate end = LocalDate.of(2026, 10, 30);

        // 5 days elapsed, £150 spent -> £30.00 / day
        SpendingPaceChunk chunk = new SpendingPaceChunk(
                1,
                "Days 1–5",
                start,
                end,
                1,
                5,
                5,
                5,
                150.0,
                30.0,
                150.0,
                5,
                30.0,
                12.56f,
                false,
                false
        );

        assertThat(chunk.getDaysInChunk()).isEqualTo(5);
        assertThat(chunk.getDaysElapsed()).isEqualTo(5);
        assertThat(chunk.getTotalSpent()).isEqualTo(150.0);
        assertThat(chunk.getSpendingPace()).isEqualTo(30.0);
        assertThat(chunk.getTargetMaxPace()).isEqualTo(12.56f);
        assertThat(chunk.isOverBudget()).isTrue();
        assertThat(chunk.getDifferenceFromMax()).isEqualTo(17.44);
        assertThat(chunk.isComplete()).isTrue();
        assertThat(chunk.getDateRangeFormatted()).isEqualTo("26 Oct – 30 Oct");
    }

    @Test
    public void testSpendingPaceCalculatorCreates5DayChunksAcrossCycle() {
        LocalDate payday = LocalDate.of(2026, 10, 26);
        LocalDate today = LocalDate.of(2026, 11, 2); // Day 8 of 31
        ZoneId zone = ZoneId.of("Europe/London");

        // Monthly surplus = £389.28. In 31 days, maxPerDay = round2(389.28 / 31) = 12.56
        Float monthlySurplus = 389.28f;
        int nominalPayday = 26;

        Account monzo = new Account();
        monzo.setId(101L);
        monzo.setName("Monzo");
        monzo.setType(Account.AccountType.DEBIT);
        monzo.balances = new ArrayList<>();

        // Day 1 (Oct 26): £500
        long oct26Sec = LocalDate.of(2026, 10, 26).atTime(9, 0).atZone(zone).toEpochSecond();
        monzo.balances.add(createBalance(monzo, 500f, oct26Sec));

        // Day 3 (Oct 28): £400 (spent £100 in Chunk 1)
        long oct28Sec = LocalDate.of(2026, 10, 28).atTime(12, 0).atZone(zone).toEpochSecond();
        monzo.balances.add(createBalance(monzo, 400f, oct28Sec));

        // Day 7 (Nov 1): £350 (spent £50 in Chunk 2)
        long nov1Sec = LocalDate.of(2026, 11, 1).atTime(14, 0).atZone(zone).toEpochSecond();
        monzo.balances.add(createBalance(monzo, 350f, nov1Sec));

        List<Account> accounts = Collections.singletonList(monzo);

        SpendingPaceViewModel vm = SpendingPaceCalculator.calculate(
                Collections.emptyList(),
                accounts,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                nominalPayday,
                today,
                zone,
                monthlySurplus,
                null,
                "testUser",
                0
        );

        assertThat(vm).isNotNull();
        assertThat(vm.isCurrentCycle()).isTrue();
        assertThat(vm.getDaysInCycle()).isEqualTo(31);
        assertThat(vm.getMaxPerDay()).isEqualTo(12.56f);

        List<SpendingPaceChunk> chunks = vm.getChunks();
        // 31 days divided by 5: [1-5], [6-10], [11-15], [16-20], [21-25], [26-30], [31]
        assertThat(chunks).hasSize(7);

        // Chunk 1: Days 1–5 (26 Oct – 30 Oct) -> Complete, spent £100, pace = 100 / 5 = £20.00 / day
        SpendingPaceChunk c1 = chunks.get(0);
        assertThat(c1.getLabel()).isEqualTo("Days 1–5");
        assertThat(c1.getDaysInChunk()).isEqualTo(5);
        assertThat(c1.getDaysElapsed()).isEqualTo(5);
        assertThat(c1.getTotalSpent()).isEqualTo(100.0);
        assertThat(c1.getSpendingPace()).isEqualTo(20.0);
        assertThat(c1.isOverBudget()).isTrue();
        assertThat(c1.isCurrent()).isFalse();
        assertThat(c1.isFuture()).isFalse();

        // Chunk 2: Days 6–10 (31 Oct – 4 Nov) -> Current (Today is Nov 2 = Day 8, so 3 days elapsed: Oct 31, Nov 1, Nov 2)
        SpendingPaceChunk c2 = chunks.get(1);
        assertThat(c2.getLabel()).isEqualTo("Days 6–10");
        assertThat(c2.getDaysInChunk()).isEqualTo(5);
        assertThat(c2.getDaysElapsed()).isEqualTo(3);
        assertThat(c2.getTotalSpent()).isEqualTo(50.0);
        // 50.0 / 3 days = 16.67 / day
        assertThat(c2.getSpendingPace()).isEqualTo(16.67);
        assertThat(c2.isCurrent()).isTrue();
        assertThat(c2.isFuture()).isFalse();

        // Chunk 3: Days 11–15 (5 Nov – 9 Nov) -> Future
        SpendingPaceChunk c3 = chunks.get(2);
        assertThat(c3.getLabel()).isEqualTo("Days 11–15");
        assertThat(c3.getDaysInChunk()).isEqualTo(5);
        assertThat(c3.getDaysElapsed()).isEqualTo(0);
        assertThat(c3.getTotalSpent()).isEqualTo(0.0);
        assertThat(c3.getSpendingPace()).isEqualTo(0.0);
        assertThat(c3.isCurrent()).isFalse();
        assertThat(c3.isFuture()).isTrue();

        // Chunk 7: Day 31 -> Future, 1 day
        SpendingPaceChunk c7 = chunks.get(6);
        assertThat(c7.getLabel()).isEqualTo("Day 31");
        assertThat(c7.getDaysInChunk()).isEqualTo(1);
        assertThat(c7.isFuture()).isTrue();

        // Total spent across cycle so far: 100 + 50 = 150.0
        assertThat(vm.getTotalSpent()).isEqualTo(150.0);

        // JSON serialization
        String json = vm.toJson();
        assertThat(json).contains("\"maxPerDay\":12.56");
        assertThat(json).contains("\"daysInCycle\":31");
        assertThat(json).contains("\"Days 1–5\"");
        assertThat(json).contains("\"Days 6–10\"");
    }
}
