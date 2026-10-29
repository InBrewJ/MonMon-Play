package models;

import com.google.inject.ImplementedBy;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

@ImplementedBy(JPAPendingStatementRepository.class)
public interface PendingStatementRepository {

    CompletionStage<PendingStatement> save(PendingStatement statement);

    CompletionStage<Optional<PendingStatement>> findById(Long id);

    CompletionStage<Optional<PendingStatement>> findByJobId(String jobId);

    CompletionStage<List<PendingStatement>> listPending(String userId);

    CompletionStage<List<PendingStatement>> listRecent(String userId, int limit);

    CompletionStage<Boolean> delete(Long id);
}
