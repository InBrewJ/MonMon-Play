package models;

import com.google.inject.ImplementedBy;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

@ImplementedBy(JPAMonzoConnectionRepository.class)
public interface MonzoConnectionRepository {

    CompletionStage<MonzoConnection> save(MonzoConnection connection);

    CompletionStage<Optional<MonzoConnection>> findByUserId(String userId);

    CompletionStage<Boolean> deleteByUserId(String userId);
}
