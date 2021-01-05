package models;

import com.google.inject.ImplementedBy;

import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

/**
 * This interface provides a non-blocking API for possibly blocking operations.
 */
@ImplementedBy(JPAIncomingRepository.class)
public interface IncomingRepository {

    CompletionStage<Incoming> add(Incoming incoming);

    CompletionStage<Stream<Incoming>> list();

}
