package models;

import com.google.inject.ImplementedBy;

import java.util.List;
import java.util.concurrent.CompletionStage;

@ImplementedBy(JPAPlaidItemRepository.class)
public interface PlaidItemRepository {

    CompletionStage<PlaidItem> save(PlaidItem item);

    CompletionStage<List<PlaidItem>> findByUserId(String userId);

    CompletionStage<PlaidItem> findByItemId(String itemId);

    CompletionStage<Boolean> delete(Long id);
}
