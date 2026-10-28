package models;

import com.google.inject.ImplementedBy;

import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

@ImplementedBy(JPACustomSpendingPlanRepository.class)
public interface CustomSpendingPlanRepository {

    CompletionStage<CustomSpendingPlan> add(CustomSpendingPlan plan);

    CompletionStage<CustomSpendingPlan> findById(Long id);

    CompletionStage<CustomSpendingPlan> createOrUpdate(String userId, String name, List<SpendingPlanSection> sections);

    CompletionStage<Stream<CustomSpendingPlan>> list(String userId);

    CompletionStage<Boolean> archive(Long id);
}
