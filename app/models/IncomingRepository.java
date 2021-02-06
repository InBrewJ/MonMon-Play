package models;

import com.google.inject.ImplementedBy;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

/**
 * This interface provides a non-blocking API for possibly blocking operations.
 */
@ImplementedBy(JPAIncomingRepository.class)
    public interface IncomingRepository {

    CompletionStage<Incoming> add(Incoming incoming);

    CompletionStage<Incoming> archive(int incomingId);

    CompletionStage<Incoming> update(int incomingId, Incoming incoming);

    CompletionStage<Incoming> findById(int incomingId);

    CompletionStage<Stream<Incoming>> list();

    CompletionStage<Stream<Incoming>> listComplete();

    int getNextPayDay() throws ExecutionException, InterruptedException;
}
