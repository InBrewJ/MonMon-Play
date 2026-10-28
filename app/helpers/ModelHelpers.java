package helpers;

import models.Incoming;
import models.Outgoing;
import models.Plan;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static helpers.MathHelpers.round2;

public class ModelHelpers {
    public static <T> List<T> repoListToList(CompletionStage<Stream<T>> in) throws ExecutionException, InterruptedException {
        return in.toCompletableFuture().get().collect(Collectors.toList());
    }

    /**
     * Calculates the actual payday for a given year and month.
     * 1. Clamps the nominal payday to the month length (e.g. 31 -> 28 in Feb).
     * 2. Shifts back to the last preceding working day taking weekends and bank holidays into account.
     */
    public static LocalDate getActualPaydayDate(int nominalPayday, int year, int month) {
        LocalDate firstOfMonth = LocalDate.of(year, month, 1);
        int maxDaysInMonth = firstOfMonth.lengthOfMonth();
        int targetDay = Math.max(1, Math.min(nominalPayday, maxDaysInMonth));
        LocalDate payDate = LocalDate.of(year, month, targetDay);
        return BankHolidayHelper.getLastWorkingDayOnOrBefore(payDate);
    }

    public static LocalDate getActualPaydayDate(int nominalPayday, LocalDate referenceDate) {
        return getActualPaydayDate(nominalPayday, referenceDate.getYear(), referenceDate.getMonthValue());
    }

    /**
     * Finds the most recent actual payday date on or before 'asOf'.
     */
    public static LocalDate findLastPaydayDate(LocalDate asOf, int nominalPayday) {
        LocalDate currentMonthPayday = getActualPaydayDate(nominalPayday, asOf);
        if (!asOf.isBefore(currentMonthPayday)) {
            return currentMonthPayday;
        } else {
            return getActualPaydayDate(nominalPayday, asOf.minusMonths(1));
        }
    }

    /**
     * Finds the next upcoming actual payday date strictly after 'asOf'.
     */
    public static LocalDate findNextPaydayDate(LocalDate asOf, int nominalPayday) {
        LocalDate currentMonthPayday = getActualPaydayDate(nominalPayday, asOf);
        if (asOf.isBefore(currentMonthPayday)) {
            return currentMonthPayday;
        } else {
            return getActualPaydayDate(nominalPayday, asOf.plusMonths(1));
        }
    }

    /**
     * Clamps an outgoing day to the last day of the month for the given date.
     */
    public static int getEffectiveOutgoingDay(int outgoingDay, LocalDate date) {
        return Math.min(outgoingDay, date.lengthOfMonth());
    }

    public static List<Outgoing> findYetToPay(List<Outgoing> outgoings, LocalDate asOf, int paydayDay) {
        if (outgoings == null || outgoings.isEmpty()) {
            return Collections.emptyList();
        }
        List<Outgoing> found = new ArrayList<>();
        LocalDate nextPayday = findNextPaydayDate(asOf, paydayDay);
        LocalDate searchDate = asOf.plusDays(1);
        while (searchDate.isBefore(nextPayday)) {
            for (Outgoing o : outgoings) {
                if (o == null || o.isArchived() || o.isPaused()) {
                    continue;
                }
                if (o.isYearly() && !o.isDueInMonth(searchDate)) {
                    continue;
                }
                int effectiveDay = getEffectiveOutgoingDay(o.getOutgoingDay(), searchDate);
                if (effectiveDay == searchDate.getDayOfMonth() && !found.contains(o)) {
                    found.add(o);
                }
            }
            searchDate = searchDate.plusDays(1);
        }
        return found;
    }

    public static List<Outgoing> findAlreadyPaid(List<Outgoing> outgoings, LocalDate asOf, int paydayDay) {
        if (outgoings == null || outgoings.isEmpty()) {
            return Collections.emptyList();
        }
        List<Outgoing> found = new ArrayList<>();
        LocalDate searchDate = findLastPaydayDate(asOf, paydayDay);
        while (!searchDate.isAfter(asOf)) {
            for (Outgoing o : outgoings) {
                if (o == null || o.isArchived() || o.isPaused()) {
                    continue;
                }
                if (o.isYearly() && !o.isDueInMonth(searchDate)) {
                    continue;
                }
                int effectiveDay = getEffectiveOutgoingDay(o.getOutgoingDay(), searchDate);
                if (effectiveDay == searchDate.getDayOfMonth() && !found.contains(o)) {
                    found.add(o);
                }
            }
            searchDate = searchDate.plusDays(1);
        }
        return found;
    }

    /**
     * Calculates the user's maximum monthly spending allowance (surplus) as configured from /spog.
     * Takes into account incomings, outgoings (with bill share and rent share plan splits applied),
     * and deductions for monthly savings goals.
     */
    public static Float calculateMonthlyAllowance(List<Incoming> incomings, List<Outgoing> outgoings, List<Plan> plans) {
        return calculateMonthlyAllowance(incomings, outgoings, plans, helpers.BankHolidayHelper.nowInRegion("UK"));
    }

    public static Float calculateMonthlyAllowance(List<Incoming> incomings, List<Outgoing> outgoings, List<Plan> plans, LocalDate asOf) {
        if (incomings == null) {
            incomings = Collections.emptyList();
        }
        if (outgoings == null) {
            outgoings = Collections.emptyList();
        }
        if (plans == null) {
            plans = Collections.emptyList();
        }

        Plan firstMonthlySavingsGoal = plans.stream()
                .filter(p -> p != null && p.getType() == Plan.PlanType.MONTHLY_SAVINGS_GOAL)
                .findFirst()
                .orElse(null);

        Plan firstRentShare = plans.stream()
                .filter(p -> p != null && p.getType() == Plan.PlanType.RENT_SHARE)
                .findFirst()
                .orElse(null);

        Plan firstBillShare = plans.stream()
                .filter(p -> p != null && p.getType() == Plan.PlanType.BILL_SHARE)
                .findFirst()
                .orElse(null);

        LocalDate effectiveAsOf = asOf != null ? asOf : helpers.BankHolidayHelper.nowInRegion("UK");

        List<Outgoing> adjustedOutgoings = new ArrayList<>();
        for (Outgoing o : outgoings) {
            if (o != null) {
                if (o.isArchived() || o.isPaused()) {
                    continue;
                }
                Outgoing copy = new Outgoing();
                copy.setId(o.getId());
                copy.setName(o.getName());
                copy.setBill(o.isBill());
                copy.setRent(o.isRent());
                copy.setHiddenFromTotal(o.isHiddenFromTotal());
                copy.setOutgoingDay(o.getOutgoingDay());
                copy.setOutgoingMonth(o.getOutgoingMonth());
                copy.setYearInterval(o.getYearInterval());
                copy.setStartingYear(o.getStartingYear());
                float cost = o.getCost();
                if (firstBillShare != null && o.isBill()) {
                    cost = cost * firstBillShare.getSplit();
                }
                if (firstRentShare != null && o.isRent()) {
                    cost = cost * firstRentShare.getSplit();
                }
                copy.setCost(cost);
                adjustedOutgoings.add(copy);
            }
        }

        Float outgoingTotal = round2(Outgoing.getTotalOutgoingsWithoutHidden(adjustedOutgoings, effectiveAsOf));
        Float incomingTotal = Incoming.getTotalIncomings(incomings);
        Float surplus = round2(incomingTotal - outgoingTotal);

        int percentIncomeAsSavings = firstMonthlySavingsGoal != null ? (int) (firstMonthlySavingsGoal.getSplit() * 100) : 0;
        Float savingsPlanCost = (((float) percentIncomeAsSavings / 100.0f) * incomingTotal);
        surplus -= savingsPlanCost;

        return round2(surplus);
    }
}
