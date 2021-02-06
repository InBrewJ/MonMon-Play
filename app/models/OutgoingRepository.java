package models;

import com.google.inject.ImplementedBy;

import java.time.LocalDate;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

/**
 * This interface provides a non-blocking API for possibly blocking operations.
 */
@ImplementedBy(JPAOutgoingRepository.class)
public interface OutgoingRepository {

    CompletionStage<Outgoing> add(Outgoing outgoing);

    CompletionStage<Stream<Outgoing>> list();

    CompletionStage<Outgoing> update(int outgoingId, Outgoing outgoing);

    CompletionStage<Outgoing> findById(int outgoingId);

    CompletionStage<Stream<Outgoing>> rents();

    CompletionStage<Stream<Outgoing>> bills();

    CompletionStage<Stream<Outgoing>> alreadyPaid(LocalDate asOf, int paydayDay);

    CompletionStage<Stream<Outgoing>> yetToPay(LocalDate asOf, int paydayDay);

}
