package models;

import com.google.inject.ImplementedBy;
import viewModels.SearchResult;

import java.util.List;
import java.util.concurrent.CompletionStage;

@ImplementedBy(JPASearchRepository.class)
public interface SearchRepository {
    CompletionStage<List<SearchResult>> search(String userId, String query, int limit);
}
