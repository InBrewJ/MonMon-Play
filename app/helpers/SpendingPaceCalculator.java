package helpers;

import models.Account;
import models.BankTransactionRepository;
import models.Incoming;
import models.Outgoing;
import models.Plan;
import models.Pot;
import viewModels.HistoricCycleSpent;
import viewModels.HistoricSpentViewModel;
import viewModels.SpendingPaceChunk;
import viewModels.SpendingPaceViewModel;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import static helpers.MathHelpers.round2;

public class SpendingPaceCalculator {

    public static SpendingPaceViewModel calculate(
            List<Pot> allPots,
            List<Account> allAccounts,
            List<Incoming> allIncomings,
            List<Outgoing> allOutgoings,
            List<Plan> allPlans,
            int nominalPayday,
            LocalDate today,
            ZoneId zone,
            Float monthlySurplus,
            BankTransactionRepository bankTransactionRepository,
            String userId,
            int cycleOffset
    ) {
        if (allAccounts == null) allAccounts = Collections.emptyList();
        if (allPots == null) allPots = Collections.emptyList();
        if (allIncomings == null) allIncomings = Collections.emptyList();
        if (allOutgoings == null) allOutgoings = Collections.emptyList();
        if (allPlans == null) allPlans = Collections.emptyList();
        if (zone == null) zone = ZoneId.of("Europe/London");
        if (today == null) today = BankHolidayHelper.nowInRegion("UK");

        // Compute historic cycles to allow switching between current and previous cycles
        HistoricSpentViewModel histVm = null;
        try {
            histVm = HistoricSpentCalculator.calculate(
                    allPots,
                    allAccounts,
                    allIncomings,
                    allOutgoings,
                    allPlans,
                    nominalPayday,
                    today,
                    zone,
                    monthlySurplus,
                    Collections.emptyMap()
            );
        } catch (Exception ignored) {}

        List<HistoricCycleSpent> availableCycles = (histVm != null && histVm.getCycles() != null)
                ? histVm.getCycles()
                : Collections.emptyList();

        HistoricCycleSpent selectedCycle = null;
        if (!availableCycles.isEmpty()) {
            int safeOffset = Math.max(0, Math.min(cycleOffset, availableCycles.size() - 1));
            selectedCycle = availableCycles.get(safeOffset);
        }

        LocalDate cycleStartDate;
        LocalDate cycleEndDate;
        boolean isCurrentCycle;
        int daysInCycle;
        int daysElapsed;
        float monthlyBudget;
        Pot activePot = null;

        if (selectedCycle != null) {
            cycleStartDate = selectedCycle.getCycleStartDate();
            cycleEndDate = selectedCycle.getCycleEndDate();
            isCurrentCycle = selectedCycle.isCurrentCycle();
            daysInCycle = selectedCycle.getDaysInCycle();
            daysElapsed = selectedCycle.getDaysElapsed();
            if (isCurrentCycle && monthlySurplus != null && monthlySurplus > 0.0f) {
                monthlyBudget = monthlySurplus;
            } else if (selectedCycle.getPotAllowance() != null && selectedCycle.getPotAllowance() > 0.0f) {
                monthlyBudget = selectedCycle.getPotAllowance();
            } else if (monthlySurplus != null && monthlySurplus > 0.0f) {
                monthlyBudget = monthlySurplus;
            } else if (selectedCycle.getMonthlyBudget() != null) {
                monthlyBudget = selectedCycle.getMonthlyBudget();
            } else {
                monthlyBudget = 0.0f;
            }
            activePot = selectedCycle.getPotConfig();
        } else {
            cycleStartDate = ModelHelpers.findLastPaydayDate(today, nominalPayday);
            cycleEndDate = ModelHelpers.findNextPaydayDate(today, nominalPayday);
            isCurrentCycle = true;
            daysInCycle = Math.max(1, (int) ChronoUnit.DAYS.between(cycleStartDate, cycleEndDate));
            daysElapsed = (int) ChronoUnit.DAYS.between(cycleStartDate, today);
            monthlyBudget = monthlySurplus != null ? monthlySurplus : 0.0f;
        }

        float maxPerDay = round2(monthlyBudget / Math.max(1, daysInCycle));

        // Resolve accounts that belong to the active pot (or all non-pension, non-loan debit accounts)
        List<Account> potAccounts = Collections.emptyList();
        if (activePot != null) {
            potAccounts = HistoricSpentCalculator.resolvePotAccounts(activePot, allAccounts);
        }
        if (potAccounts.isEmpty()) {
            List<Pot> monthlyPots = allPots.stream()
                    .filter(p -> p != null && p.getType() == Pot.PotType.MONTHLY)
                    .sorted(Comparator.comparing(p -> p.getCreatedAt() != null ? p.getCreatedAt() : 0L))
                    .collect(Collectors.toList());
            if (!monthlyPots.isEmpty()) {
                potAccounts = HistoricSpentCalculator.resolvePotAccounts(monthlyPots.get(monthlyPots.size() - 1), allAccounts);
            }
        }
        if (potAccounts.isEmpty()) {
            potAccounts = allAccounts.stream()
                    .filter(a -> a != null && !a.isArchived()
                            && a.getType() != Account.AccountType.PENSION
                            && a.getType() != Account.AccountType.LOAN)
                    .collect(Collectors.toList());
        }

        Map<Long, Account> uniqueAccounts = new LinkedHashMap<>();
        for (Account a : potAccounts) {
            if (a != null) {
                Long id = a.getId() != null ? a.getId() : (long) a.hashCode();
                uniqueAccounts.putIfAbsent(id, a);
            }
        }

        // Divide into 5-day chunks
        List<SpendingPaceChunk> chunks = new ArrayList<>();
        int chunkIndex = 1;
        double cumulativeSpent = 0.0;
        int cumulativeDaysElapsed = 0;

        for (int startDay = 1; startDay <= daysInCycle; startDay += 5) {
            int endDay = Math.min(startDay + 4, daysInCycle);
            int daysInChunk = endDay - startDay + 1;
            LocalDate chunkStartDate = cycleStartDate.plusDays(startDay - 1);
            LocalDate chunkEndDate = cycleStartDate.plusDays(endDay - 1);

            long chunkStartSec = chunkStartDate.atStartOfDay(zone).toEpochSecond();
            long chunkEndSec = chunkEndDate.atTime(LocalTime.MAX).atZone(zone).toEpochSecond();

            boolean isCurrentChunk = false;
            boolean isFutureChunk = false;
            int chunkDaysElapsed = 0;

            if (isCurrentCycle) {
                if (today.isBefore(chunkStartDate)) {
                    isFutureChunk = true;
                    chunkDaysElapsed = 0;
                } else if (today.isAfter(chunkEndDate)) {
                    chunkDaysElapsed = daysInChunk;
                } else {
                    isCurrentChunk = true;
                    chunkDaysElapsed = (int) ChronoUnit.DAYS.between(chunkStartDate, today) + 1;
                    chunkDaysElapsed = Math.min(chunkDaysElapsed, daysInChunk);
                }
            } else {
                chunkDaysElapsed = daysInChunk;
            }

            double chunkSpent = 0.0;
            if (!isFutureChunk) {
                long effectiveEndSec = isCurrentChunk
                        ? today.atTime(LocalTime.MAX).atZone(zone).toEpochSecond()
                        : chunkEndSec;

                Map<Long, Double> chunkTblMap = null;
                if (bankTransactionRepository != null && userId != null) {
                    try {
                        chunkTblMap = bankTransactionRepository.getAccountsSpentSumMap(userId, chunkStartSec, effectiveEndSec)
                                .toCompletableFuture().get();
                    } catch (Exception ignored) {}
                }

                for (Account acc : uniqueAccounts.values()) {
                    boolean isTblActive = acc.getLedgerMode() == Account.LedgerMode.TBL
                            && acc.getType() != Account.AccountType.PENSION
                            && acc.getType() != Account.AccountType.LOAN;
                    if (isTblActive && chunkTblMap != null && chunkTblMap.containsKey(acc.getId())) {
                        chunkSpent += chunkTblMap.get(acc.getId());
                    } else {
                        chunkSpent += HistoricSpentCalculator.calculateAccountSpent(acc, chunkStartSec, effectiveEndSec);
                    }
                }
                chunkSpent = round2(Math.max(0.0, chunkSpent));
            }

            double spendingPace = chunkDaysElapsed > 0 ? round2(chunkSpent / chunkDaysElapsed) : 0.0;

            cumulativeSpent = round2(cumulativeSpent + chunkSpent);
            cumulativeDaysElapsed += chunkDaysElapsed;
            double cumulativePace = cumulativeDaysElapsed > 0 ? round2(cumulativeSpent / cumulativeDaysElapsed) : 0.0;

            String chunkLabel = (startDay == endDay)
                    ? "Day " + startDay
                    : "Days " + startDay + "–" + endDay;

            SpendingPaceChunk chunk = new SpendingPaceChunk(
                    chunkIndex++,
                    chunkLabel,
                    chunkStartDate,
                    chunkEndDate,
                    startDay,
                    endDay,
                    daysInChunk,
                    chunkDaysElapsed,
                    chunkSpent,
                    spendingPace,
                    cumulativeSpent,
                    cumulativeDaysElapsed,
                    cumulativePace,
                    maxPerDay,
                    isCurrentChunk,
                    isFutureChunk
            );
            chunks.add(chunk);
        }

        double totalCycleSpent = cumulativeSpent;
        double actualDailySpend = (daysElapsed > 0) ? round2(totalCycleSpent / daysElapsed) : 0.0;

        return new SpendingPaceViewModel(
                cycleStartDate,
                cycleEndDate,
                isCurrentCycle,
                daysInCycle,
                daysElapsed,
                monthlyBudget,
                maxPerDay,
                totalCycleSpent,
                actualDailySpend,
                cycleOffset,
                chunks,
                availableCycles
        );
    }
}
