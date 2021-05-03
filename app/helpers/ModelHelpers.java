package helpers;

import models.Outgoing;

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

    public static List<Outgoing> findYetToPay(List<Outgoing> outgoings, LocalDate asOf, int paydayDay) {
        List<Outgoing> paid = findAlreadyPaid(outgoings, asOf, paydayDay);
        outgoings.removeAll(paid);
        return outgoings;
    }

    public static List<Outgoing> findAlreadyPaid(List<Outgoing> outgoings, LocalDate asOf, int paydayDay) {
        System.out.println("We're here...");
        System.out.println("length of outgoings :: " + outgoings.size());

        List<Outgoing> found = new ArrayList<>();
        LocalDate searchDate = findLastPaydayDate(asOf, paydayDay);
        do {
            for (Outgoing o: outgoings) {
                if (o.getOutgoingDay() == searchDate.getDayOfMonth()) {
                    found.add(o);
                }
            }
            searchDate = searchDate.plusDays(1);
        } while (searchDate.getDayOfMonth() != asOf.plusDays(1).getDayOfMonth());
        return found;
    }

    public static LocalDate findLastPaydayDate(LocalDate asOf, int payday) {
        // This should NOT be greater than a month ago!
        // For example, if today is the 31st of March and we search
        // for 31st Feb, we're going to go all the way back to the 31st Jan

        LocalDate possiblePayDate = asOf;
        while(possiblePayDate.getDayOfMonth() != payday) {
            // If 'payday' is not in this month, return the last day
            // of this month
            int endOfThisMonth = possiblePayDate.lengthOfMonth();
            if (payday > endOfThisMonth) {
                return possiblePayDate.withDayOfMonth(endOfThisMonth);
            }
            possiblePayDate = possiblePayDate.minusDays(1);
        }
        return possiblePayDate;
    }
}
