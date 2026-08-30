package helpers;

import models.Outgoing;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
        List<Outgoing> paid = findAlreadyPaid(outgoings, asOf, paydayDay);
        List<Outgoing> remaining = new ArrayList<>(outgoings);
        remaining.removeAll(paid);
        return remaining;
    }

    public static List<Outgoing> findAlreadyPaid(List<Outgoing> outgoings, LocalDate asOf, int paydayDay) {
        List<Outgoing> found = new ArrayList<>();
        LocalDate searchDate = findLastPaydayDate(asOf, paydayDay);
        while (!searchDate.isAfter(asOf)) {
            for (Outgoing o : outgoings) {
                int effectiveDay = getEffectiveOutgoingDay(o.getOutgoingDay(), searchDate);
                if (effectiveDay == searchDate.getDayOfMonth() && !found.contains(o)) {
                    found.add(o);
                }
            }
            searchDate = searchDate.plusDays(1);
        }
        return found;
    }
}
