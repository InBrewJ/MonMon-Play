package helpers;

import models.Account;
import models.Balance;
import models.Pot;
import viewModels.AccountSpentItem;
import viewModels.HistoricCycleSpent;
import viewModels.HistoricSpentViewModel;
import viewModels.Spog;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import static helpers.MathHelpers.round2;

public class HistoricSpentCalculator {

    public static HistoricSpentViewModel calculate(
            List<Pot> allPots,
            List<Account> allAccounts,
            int nominalPayday,
            LocalDate today,
            ZoneId zone,
            Float monthlySurplus
    ) {
        if (allAccounts == null) {
            allAccounts = Collections.emptyList();
        }
        if (allPots == null) {
            allPots = Collections.emptyList();
        }

        List<Pot> monthlyPots = allPots.stream()
                .filter(p -> p != null && p.getType() == Pot.PotType.MONTHLY)
                .sorted(Comparator.comparing(p -> p.getCreatedAt() != null ? p.getCreatedAt() : 0L))
                .collect(Collectors.toList());

        LocalDate currentCycleStart = ModelHelpers.findLastPaydayDate(today, nominalPayday);
        LocalDate currentCycleEnd = ModelHelpers.findNextPaydayDate(today, nominalPayday);

        // Find earliest date across all account balances
        long minBalanceSec = Long.MAX_VALUE;
        for (Account a : allAccounts) {
            if (a.balances != null) {
                for (Balance b : a.balances) {
                    if (b != null && b.getTimestamp() != null) {
                        long t = toEpochSec(b.getTimestamp());
                        if (t > 0 && t < minBalanceSec) {
                            minBalanceSec = t;
                        }
                    }
                }
            }
        }

        LocalDate earliestDate;
        if (minBalanceSec != Long.MAX_VALUE) {
            earliestDate = Instant.ofEpochSecond(minBalanceSec).atZone(zone).toLocalDate();
        } else {
            earliestDate = today.minusMonths(6);
        }

        // Limit range: at least 6 months back, at most 24 months back
        LocalDate minAllowed = today.minusMonths(6);
        if (earliestDate.isAfter(minAllowed)) {
            earliestDate = minAllowed;
        }
        LocalDate maxAllowed = today.minusMonths(24);
        if (earliestDate.isBefore(maxAllowed)) {
            earliestDate = maxAllowed;
        }

        List<HistoricCycleSpent> cycles = new ArrayList<>();
        LocalDate currCycleStart = currentCycleStart;

        while (!currCycleStart.isBefore(earliestDate)) {
            LocalDate currCycleEnd = ModelHelpers.findNextPaydayDate(currCycleStart, nominalPayday);
            boolean isCurrent = !today.isBefore(currCycleStart) && today.isBefore(currCycleEnd);

            long cycleStartSec = currCycleStart.atStartOfDay(zone).toEpochSecond();
            long cycleEndSec = currCycleEnd.atStartOfDay(zone).toEpochSecond();
            long effectiveEndSec = isCurrent ? today.atTime(LocalTime.MAX).atZone(zone).toEpochSecond() : cycleEndSec;

            // Find which pot configuration was active during this cycle
            Pot activePot = findActivePotForCycle(monthlyPots, cycleStartSec, cycleEndSec);

            // Accounts associated with this pot configuration.
            // Legacy archived pots (pre pot_accounts join table) may have lost their accounts,
            // so borrow from the nearest later pot config that still has some.
            Pot accountSourcePot = activePot;
            if (activePot != null && (activePot.accounts == null || activePot.accounts.isEmpty())) {
                int idx = monthlyPots.indexOf(activePot);
                for (int i = idx + 1; i < monthlyPots.size(); i++) {
                    Pot later = monthlyPots.get(i);
                    if (later.accounts != null && !later.accounts.isEmpty()) {
                        accountSourcePot = later;
                        break;
                    }
                }
            }
            List<Account> potAccounts = resolvePotAccounts(accountSourcePot, allAccounts);

            double cycleSpent = 0.0;
            double startingPotValue = 0.0;
            double endingPotValue = 0.0;
            List<AccountSpentItem> accountItems = new ArrayList<>();

            for (Account acc : potAccounts) {
                double startBal = getAccountStartBalance(acc, cycleStartSec, cycleEndSec);
                double endBal = getAccountEndBalance(acc, cycleStartSec, cycleEndSec, isCurrent, effectiveEndSec);
                double accSpent = calculateAccountSpent(acc, cycleStartSec, effectiveEndSec);

                if (accSpent <= 0.0 && startBal > endBal) {
                    accSpent = round2(startBal - endBal);
                }

                startingPotValue += startBal;
                endingPotValue += endBal;
                cycleSpent += accSpent;

                accountItems.add(new AccountSpentItem(
                        acc.getName() != null ? acc.getName() : "Account #" + acc.getId(),
                        acc.getType() != null ? acc.getType().name() : "DEBIT",
                        startBal,
                        endBal,
                        accSpent
                ));
            }

            cycleSpent = round2(cycleSpent);
            startingPotValue = round2(startingPotValue);
            endingPotValue = round2(endingPotValue);

            int daysInCycle = Math.max(1, (int) ChronoUnit.DAYS.between(currCycleStart, currCycleEnd));
            int daysElapsed = isCurrent ? Math.max(1, (int) ChronoUnit.DAYS.between(currCycleStart, today)) : daysInCycle;
            double actualDailySpend = round2(daysElapsed > 0 ? cycleSpent / daysElapsed : 0.0);

            cycles.add(new HistoricCycleSpent(
                    currCycleStart,
                    currCycleEnd,
                    isCurrent,
                    daysInCycle,
                    daysElapsed,
                    cycleSpent,
                    actualDailySpend,
                    startingPotValue,
                    endingPotValue,
                    monthlySurplus,
                    activePot,
                    accountItems
            ));

            // Move to previous cycle
            LocalDate prevDay = currCycleStart.minusDays(1);
            currCycleStart = ModelHelpers.findLastPaydayDate(prevDay, nominalPayday);
        }

        return new HistoricSpentViewModel(cycles);
    }

    public static double getAccountStartBalance(Account acc, long cycleStartSec, long cycleEndSec) {
        if (acc == null || acc.balances == null || acc.balances.isEmpty()) {
            return 0.0;
        }
        List<Balance> valid = acc.balances.stream()
                .filter(b -> b != null && b.getTimestamp() != null && b.getValue() != null)
                .sorted(Comparator.comparing((Balance b) -> toEpochSec(b.getTimestamp()))
                        .thenComparing(b -> b.getId() != null ? b.getId() : 0L))
                .collect(Collectors.toList());

        if (valid.isEmpty()) {
            return 0.0;
        }

        // 1. Balance near cycle start (within 24 hours around cycleStartSec)
        Balance bestNearStart = null;
        long bestDist = Long.MAX_VALUE;
        for (Balance b : valid) {
            long t = toEpochSec(b.getTimestamp());
            long dist = Math.abs(t - cycleStartSec);
            if (dist <= 86400L && dist < bestDist) {
                bestDist = dist;
                bestNearStart = b;
            }
        }
        if (bestNearStart != null) {
            return round2((double) bestNearStart.getValue());
        }

        // 2. Earliest balance recorded within the cycle
        for (Balance b : valid) {
            long t = toEpochSec(b.getTimestamp());
            if (t >= cycleStartSec && t < cycleEndSec) {
                return round2((double) b.getValue());
            }
        }

        // 3. Fallback: latest balance recorded before cycleStartSec
        Balance latestBefore = null;
        for (Balance b : valid) {
            long t = toEpochSec(b.getTimestamp());
            if (t < cycleStartSec) {
                latestBefore = b;
            } else {
                break;
            }
        }
        if (latestBefore != null) {
            return round2((double) latestBefore.getValue());
        }

        return round2((double) valid.get(0).getValue());
    }

    public static double getAccountEndBalance(Account acc, long cycleStartSec, long cycleEndSec, boolean isCurrentCycle, long nowSec) {
        if (acc == null || acc.balances == null || acc.balances.isEmpty()) {
            return 0.0;
        }
        List<Balance> valid = acc.balances.stream()
                .filter(b -> b != null && b.getTimestamp() != null && b.getValue() != null)
                .sorted(Comparator.comparing((Balance b) -> toEpochSec(b.getTimestamp()))
                        .thenComparing(b -> b.getId() != null ? b.getId() : 0L))
                .collect(Collectors.toList());

        if (valid.isEmpty()) {
            return 0.0;
        }

        // For current cycle, latest balance recorded up to now
        if (isCurrentCycle) {
            Balance latest = null;
            for (Balance b : valid) {
                long t = toEpochSec(b.getTimestamp());
                if (t <= nowSec) {
                    latest = b;
                }
            }
            if (latest != null) {
                return round2((double) latest.getValue());
            }
            return round2((double) valid.get(valid.size() - 1).getValue());
        }

        // For completed past cycle: latest balance within the cycle (up to cycleEndSec)
        Balance latestInCycle = null;
        for (Balance b : valid) {
            long t = toEpochSec(b.getTimestamp());
            if (t >= cycleStartSec && t <= cycleEndSec) {
                latestInCycle = b;
            }
        }
        if (latestInCycle != null) {
            return round2((double) latestInCycle.getValue());
        }

        // Otherwise latest before cycleEndSec
        Balance latestBeforeEnd = null;
        for (Balance b : valid) {
            long t = toEpochSec(b.getTimestamp());
            if (t < cycleEndSec) {
                latestBeforeEnd = b;
            }
        }
        if (latestBeforeEnd != null) {
            return round2((double) latestBeforeEnd.getValue());
        }

        return round2((double) valid.get(0).getValue());
    }

    /**
     * Picks the monthly pot config that was active for the largest part of the cycle.
     * Pots are expected sorted by createdAt. Only one monthly pot is active at a time
     * (creating a new one soft-deletes the old), so a pot's active period runs from its
     * createdAt until the next pot's createdAt (or its own archive time if earlier).
     */
    public static Pot findActivePotForCycle(List<Pot> pots, long cycleStartSec, long cycleEndSec) {
        if (pots == null || pots.isEmpty()) {
            return null;
        }

        Pot best = null;
        long bestOverlap = 0;
        for (int i = 0; i < pots.size(); i++) {
            Pot p = pots.get(i);
            long start = p.getCreatedAt() != null ? p.getCreatedAt() : Long.MIN_VALUE;
            long end = (i + 1 < pots.size() && pots.get(i + 1).getCreatedAt() != null)
                    ? pots.get(i + 1).getCreatedAt()
                    : Long.MAX_VALUE;
            if (p.isArchived() && p.getLastUpdated() != null && p.getCreatedAt() != null
                    && p.getLastUpdated() > p.getCreatedAt()) {
                end = Math.min(end, p.getLastUpdated());
            }
            long overlap = Math.min(end, cycleEndSec) - Math.max(start, cycleStartSec);
            if (overlap > 0 && overlap >= bestOverlap) {
                best = p;
                bestOverlap = overlap;
            }
        }

        if (best != null) {
            return best;
        }
        return pots.get(0);
    }

    public static List<Account> resolvePotAccounts(Pot pot, List<Account> allAccounts) {
        if (pot == null || pot.accounts == null || pot.accounts.isEmpty()) {
            if (allAccounts != null) {
                return allAccounts.stream()
                        .filter(a -> a.getType() == Account.AccountType.DEBIT)
                        .collect(Collectors.toList());
            }
            return Collections.emptyList();
        }

        Map<Long, Account> accountById = new HashMap<>();
        if (allAccounts != null) {
            for (Account a : allAccounts) {
                if (a != null && a.getId() != null) {
                    accountById.put(a.getId(), a);
                }
            }
        }

        List<Account> resolved = new ArrayList<>();
        for (Account pa : pot.accounts) {
            if (pa != null) {
                Long id = pa.getId();
                if (id != null && accountById.containsKey(id)) {
                    resolved.add(accountById.get(id));
                } else {
                    resolved.add(pa);
                }
            }
        }
        return resolved;
    }

    public static double calculateAccountSpent(Account acc, long startSec, long endSec) {
        if (acc == null || acc.balances == null || acc.balances.isEmpty()) {
            return 0.0;
        }

        List<Balance> valid = acc.balances.stream()
                .filter(b -> b != null && b.getTimestamp() != null && b.getValue() != null)
                .sorted(Comparator.comparing((Balance b) -> toEpochSec(b.getTimestamp()))
                        .thenComparing(b -> b.getId() != null ? b.getId() : 0L))
                .collect(Collectors.toList());

        if (valid.size() < 2) {
            return 0.0;
        }

        double totalSpent = 0.0;
        for (int i = 1; i < valid.size(); i++) {
            Balance curr = valid.get(i);
            long tCurr = toEpochSec(curr.getTimestamp());
            if (tCurr >= startSec && tCurr < endSec) {
                Balance prev = valid.get(i - 1);
                double delta = (double) curr.getValue() - (double) prev.getValue();
                if (delta < 0) {
                    totalSpent += (-delta);
                }
            }
        }
        return round2(Math.max(0.0, totalSpent));
    }

    private static long toEpochSec(Long timestamp) {
        if (timestamp == null) {
            return 0L;
        }
        return timestamp > 10_000_000_000L ? timestamp / 1000L : timestamp;
    }
}
