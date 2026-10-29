package models;

import com.google.inject.ImplementedBy;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

@ImplementedBy(JPAMealPlanRepository.class)
public interface MealPlanRepository {

    CompletionStage<LockedMealPlan> saveOrUpdate(LockedMealPlan plan);

    CompletionStage<Optional<LockedMealPlan>> findByUserId(String userId);

    CompletionStage<Boolean> deleteByUserId(String userId);
}
