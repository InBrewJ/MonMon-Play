package models;

import play.db.jpa.JPAApi;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.persistence.EntityManager;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.stream.Stream;

import static java.util.concurrent.CompletableFuture.supplyAsync;

@Singleton
public class JPANetWorthAssetRepository implements NetWorthAssetRepository {

    private final JPAApi jpaApi;
    private final DatabaseExecutionContext executionContext;

    @Inject
    public JPANetWorthAssetRepository(JPAApi jpaApi, DatabaseExecutionContext executionContext) {
        this.jpaApi = jpaApi;
        this.executionContext = executionContext;
    }

    private <T> T wrap(Function<EntityManager, T> function) {
        return jpaApi.withTransaction(function);
    }

    @Override
    public CompletionStage<NetWorthAsset> add(NetWorthAsset asset) {
        return supplyAsync(() -> wrap(em -> {
            if (asset.createdAt == null) asset.createdAt = System.currentTimeMillis();
            asset.updatedAt = System.currentTimeMillis();
            em.persist(asset);
            return asset;
        }), executionContext);
    }

    @Override
    public CompletionStage<Optional<NetWorthAsset>> findById(Long id) {
        return supplyAsync(() -> wrap(em -> {
            NetWorthAsset a = em.find(NetWorthAsset.class, id);
            return Optional.ofNullable(a);
        }), executionContext);
    }

    @Override
    public CompletionStage<Stream<NetWorthAsset>> list(String userId) {
        return supplyAsync(() -> wrap(em -> {
            try {
                List<NetWorthAsset> list = em.createQuery(
                        "select a from NetWorthAsset a where a.userId = :userId and a.archived = false order by a.value desc, a.createdAt asc",
                        NetWorthAsset.class)
                        .setParameter("userId", userId)
                        .getResultList();
                return list.stream();
            } catch (Exception e) {
                return Stream.empty();
            }
        }), executionContext);
    }

    @Override
    public CompletionStage<Stream<NetWorthAsset>> listAll(String userId) {
        return supplyAsync(() -> wrap(em -> {
            try {
                List<NetWorthAsset> list = em.createQuery(
                        "select a from NetWorthAsset a where a.userId = :userId order by a.createdAt asc",
                        NetWorthAsset.class)
                        .setParameter("userId", userId)
                        .getResultList();
                return list.stream();
            } catch (Exception e) {
                return Stream.empty();
            }
        }), executionContext);
    }

    @Override
    public CompletionStage<NetWorthAsset> update(Long id, String name, NetWorthAsset.AssetCategory category, Double value, String notes) {
        return update(id, name, category, value, notes, null);
    }

    @Override
    public CompletionStage<NetWorthAsset> update(Long id, String name, NetWorthAsset.AssetCategory category, Double value, String notes, Long acquiredAt) {
        return supplyAsync(() -> wrap(em -> {
            NetWorthAsset a = em.find(NetWorthAsset.class, id);
            if (a != null) {
                if (name != null) a.setName(name);
                if (category != null) a.setCategory(category);
                if (value != null) a.setValue(value);
                if (notes != null) a.setNotes(notes);
                if (acquiredAt != null && acquiredAt > 0) a.setAcquiredAt(acquiredAt);
                a.setUpdatedAt(System.currentTimeMillis());
                em.merge(a);
            }
            return a;
        }), executionContext);
    }

    @Override
    public CompletionStage<Boolean> archive(Long id) {
        return supplyAsync(() -> wrap(em -> {
            NetWorthAsset a = em.find(NetWorthAsset.class, id);
            if (a != null) {
                long now = System.currentTimeMillis();
                a.setArchived(true);
                a.setDeletedAt(now);
                a.setUpdatedAt(now);
                em.merge(a);
                return true;
            }
            return false;
        }), executionContext);
    }
}
