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

    CompletionStage<SavingsPlanItem> findItemById(Long itemId);

    CompletionStage<SavingsPlanItem> toggleLock(Long itemId);

    CompletionStage<SavingsPlan> balanceEvenly(Long planId, Float incomingTotal);

    CompletionStage<SavingsPlan> updateItemAmounts(Long planId, java.util.Map<Long, Float> amounts);

    CompletionStage<SavingsPlanItem> updateItem(Long itemId, String name, SavingsPlanItem.ItemType type, Long accountId, Float targetAmount, Float monthlyAmount, Integer priority);

    CompletionStage<Boolean> archiveItem(Long itemId);

    CompletionStage<Boolean> moveItemUp(Long itemId);

    CompletionStage<Boolean> moveItemDown(Long itemId);

    CompletionStage<Boolean> moveToTop(Long itemId);

    CompletionStage<Boolean> moveToBottom(Long itemId);

    CompletionStage<SavingsPlan> updatePercentage(Long planId, int percent);

    CompletionStage<SavingsPlan> updatePercentageAndScaleItems(Long planId, int percent, Float incomingTotal);

    CompletionStage<Stream<SavingsPlan>> list(String userId);

    CompletionStage<Stream<SavingsPlan>> listComplete(String userId);
}
