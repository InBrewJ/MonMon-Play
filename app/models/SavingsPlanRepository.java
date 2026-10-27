package models;

import com.google.inject.ImplementedBy;

import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

@ImplementedBy(JPASavingsPlanRepository.class)
public interface SavingsPlanRepository {

    CompletionStage<SavingsPlan> add(SavingsPlan plan);

    CompletionStage<SavingsPlan> createOrReplace(String userId, SavingsPlan plan);

    CompletionStage<SavingsPlan> getActive(String userId);

    CompletionStage<SavingsPlan> findById(Long id);

    CompletionStage<SavingsPlan> archive(Long planId);

    CompletionStage<SavingsPlanItem> addItem(Long planId, SavingsPlanItem item);

    CompletionStage<Boolean> archiveItem(Long itemId);

    CompletionStage<SavingsPlan> updatePercentage(Long planId, int percent);

    CompletionStage<Stream<SavingsPlan>> list(String userId);

    CompletionStage<Stream<SavingsPlan>> listComplete(String userId);
}
