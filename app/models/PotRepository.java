package models;

import com.google.inject.ImplementedBy;

import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

/**
 * This interface provides a non-blocking API for possibly blocking operations.
 */
@ImplementedBy(JPAPotRepository.class)
public interface PotRepository {

    CompletionStage<Pot> add(Pot pot);

    // List _with_ archived
    CompletionStage<Stream<Pot>> listComplete(String userId);

//    CompletionStage<Pot> update(int potId, Pot pot);
//
//    CompletionStage<Pot> archive(int potId);
//
//    // List _without_ archived (for displayed)
//    CompletionStage<Stream<Pot>> list(String userId);

}
