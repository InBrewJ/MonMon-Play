package helpers;

import models.Account;
import models.Balance;
import models.NetWorthAsset;
import org.junit.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class NetWorthHistoryCalculatorTest {

    @Test
    public void testThreeLinesAndSoldAssetDropsToZero() {
        ZoneId zone = ZoneId.of("Europe/London");
        LocalDate today = LocalDate.of(2026, 10, 28);

        long twoYearsAgoSec = today.minusYears(2).atStartOfDay(zone).toEpochSecond();
        long oneYearAgoSec = today.minusYears(1).atStartOfDay(zone).toEpochSecond();
        long sixMonthsAgoSec = today.minusMonths(6).atStartOfDay(zone).toEpochSecond();

        // 1. Pension Account
        Account pension = new Account();
        pension.setUserId("u-1");
        pension.setName("Nest Pension");
        pension.setType(Account.AccountType.PENSION);
        pension.setId(1L);
        Balance pb1 = new Balance();
        pb1.setId(101L);
        pb1.setValue(30000.0f);
        pb1.setTimestamp(twoYearsAgoSec);

        Balance pb2 = new Balance();
        pb2.setId(102L);
        pb2.setValue(40000.0f);
        pb2.setTimestamp(oneYearAgoSec);
        pension.balances = List.of(pb1, pb2);

        // 2. Cash & Savings Accounts (Debit and Long Term Savings on one line)
        Account currentAcc = new Account();
        currentAcc.setUserId("u-1");
        currentAcc.setName("Main Current");
        currentAcc.setType(Account.AccountType.DEBIT);
        currentAcc.setId(2L);
        Balance cb1 = new Balance();
        cb1.setId(201L);
        cb1.setValue(5000.0f);
        cb1.setTimestamp(twoYearsAgoSec);

        Balance cb2 = new Balance();
        cb2.setId(202L);
        cb2.setValue(7000.0f);
        cb2.setTimestamp(oneYearAgoSec);
        currentAcc.balances = List.of(cb1, cb2);

        Account savingsAcc = new Account();
        savingsAcc.setUserId("u-1");
        savingsAcc.setName("ISA Savings");
        savingsAcc.setType(Account.AccountType.LONG_TERM_SAVINGS);
        savingsAcc.setId(3L);
        Balance sb1 = new Balance();
        sb1.setId(301L);
        sb1.setValue(10000.0f);
        sb1.setTimestamp(twoYearsAgoSec);

        Balance sb2 = new Balance();
        sb2.setId(302L);
        sb2.setValue(12000.0f);
        sb2.setTimestamp(oneYearAgoSec);
        savingsAcc.balances = List.of(sb1, sb2);

        // 3. Fixed Assets
        // Flat: created 3 years ago, active
        NetWorthAsset flat = new NetWorthAsset("u-1", "Edinburgh Flat", NetWorthAsset.AssetCategory.PROPERTY, 200000.0, "Home");
        flat.setId(10L);
        flat.createdAt = today.minusYears(3).atStartOfDay(zone).toEpochSecond() * 1000L;
        flat.updatedAt = flat.createdAt;
        flat.archived = false;

        // Car: created 2 years ago, sold (archived) 6 months ago
        NetWorthAsset car = new NetWorthAsset("u-1", "Ford Fiesta", NetWorthAsset.AssetCategory.VEHICLE, 10000.0, "Sold");
        car.setId(11L);
        car.createdAt = twoYearsAgoSec * 1000L;
        car.updatedAt = sixMonthsAgoSec * 1000L;
        car.archived = true;

        List<Account> accounts = List.of(pension, currentAcc, savingsAcc);
        List<NetWorthAsset> assets = List.of(flat, car);

        List<NetWorthHistoryCalculator.HistoryPoint> points =
                NetWorthHistoryCalculator.calculate(accounts, null, assets, today, zone);

        assertThat(points).isNotEmpty();

        // Find snapshot point at 2 years ago
        NetWorthHistoryCalculator.HistoryPoint atTwoYears = points.stream()
                .filter(p -> p.getTimestamp() >= twoYearsAgoSec && p.getTimestamp() < oneYearAgoSec)
                .findFirst()
                .orElseThrow();
        assertThat(atTwoYears.getPensions()).isEqualTo(30000.0);
        assertThat(atTwoYears.getCashSavings()).isEqualTo(15000.0); // 5k + 10k
        assertThat(atTwoYears.getFixedAssets()).isEqualTo(210000.0); // 200k + 10k
        assertThat(atTwoYears.getTotal()).isEqualTo(255000.0);

        // Find snapshot point at 1 year ago (car still owned)
        NetWorthHistoryCalculator.HistoryPoint atOneYear = points.stream()
                .filter(p -> p.getTimestamp() >= oneYearAgoSec && p.getTimestamp() < sixMonthsAgoSec)
                .findFirst()
                .orElseThrow();
        assertThat(atOneYear.getPensions()).isEqualTo(40000.0);
        assertThat(atOneYear.getCashSavings()).isEqualTo(19000.0); // 7k + 12k
        assertThat(atOneYear.getFixedAssets()).isEqualTo(210000.0); // 200k + 10k
        assertThat(atOneYear.getTotal()).isEqualTo(269000.0);

        // Find snapshot point today (car is sold, so fixed assets drops by 10k to 200k)
        NetWorthHistoryCalculator.HistoryPoint latestPoint = points.get(points.size() - 1);
        assertThat(latestPoint.getPensions()).isEqualTo(40000.0);
        assertThat(latestPoint.getCashSavings()).isEqualTo(19000.0);
        assertThat(latestPoint.getFixedAssets()).isEqualTo(200000.0); // Car is sold -> 0 onwards!
        assertThat(latestPoint.getTotal()).isEqualTo(259000.0);

        // Test JSON serialization
        String json = NetWorthHistoryCalculator.buildChartHistoryJson(points);
        assertThat(json).contains("\"pensions\":40000.0");
        assertThat(json).contains("\"cashSavings\":19000.0");
        assertThat(json).contains("\"fixedAssets\":200000.0");
    }

    @Test
    public void testAssetAddedTodayShowsAcrossHistoryUntilArchived() {
        ZoneId zone = ZoneId.of("Europe/London");
        LocalDate today = LocalDate.of(2026, 10, 28);

        // User enters their house today
        NetWorthAsset house = new NetWorthAsset("u-1", "House", NetWorthAsset.AssetCategory.PROPERTY, 350000.0, "Family home");
        house.setId(12L);
        house.createdAt = System.currentTimeMillis();
        house.updatedAt = System.currentTimeMillis();
        house.archived = false;

        List<NetWorthHistoryCalculator.HistoryPoint> points =
                NetWorthHistoryCalculator.calculate(java.util.Collections.emptyList(), null, List.of(house), today, zone);

        assertThat(points).isNotEmpty();
        for (NetWorthHistoryCalculator.HistoryPoint p : points) {
            assertThat(p.getFixedAssets()).isEqualTo(350000.0);
            assertThat(p.getTotal()).isEqualTo(350000.0);
        }
    }
}
