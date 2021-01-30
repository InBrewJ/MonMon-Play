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
        LocalDate possiblePayDate = asOf;
        while(possiblePayDate.getDayOfMonth() != payday) {
            possiblePayDate = possiblePayDate.minusDays(1);
        }
        return possiblePayDate;
    }
}
