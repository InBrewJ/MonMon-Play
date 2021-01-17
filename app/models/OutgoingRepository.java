package models;

import com.google.inject.ImplementedBy;

import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

/**
 * This interface provides a non-blocking API for possibly blocking operations.
 */
@ImplementedBy(JPAOutgoingRepository.class)
public interface OutgoingRepository {

    CompletionStage<Outgoing> add(Outgoing outgoing);

    CompletionStage<Stream<Outgoing>> list();

    CompletionStage<Stream<Outgoing>> rents();

    // For the future...
    //    CompletionStage<Stream<Outgoing>> bills();

}
