package models;

import com.google.inject.ImplementedBy;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

@ImplementedBy(JPANetWorthAssetRepository.class)
public interface NetWorthAssetRepository {

    CompletionStage<NetWorthAsset> add(NetWorthAsset asset);

    CompletionStage<Optional<NetWorthAsset>> findById(Long id);

    CompletionStage<Stream<NetWorthAsset>> list(String userId);

    CompletionStage<Stream<NetWorthAsset>> listAll(String userId);

    CompletionStage<NetWorthAsset> update(Long id, String name, NetWorthAsset.AssetCategory category, Double value, String notes);

    CompletionStage<Boolean> archive(Long id);
}
