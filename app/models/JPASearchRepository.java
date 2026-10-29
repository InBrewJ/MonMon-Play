package models;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.db.jpa.JPAApi;
import viewModels.SearchResult;

import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.persistence.Query;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static java.util.concurrent.CompletableFuture.supplyAsync;

public class JPASearchRepository implements SearchRepository {

    private static final Logger logger = LoggerFactory.getLogger(JPASearchRepository.class);

    private final JPAApi jpaApi;
    private final DatabaseExecutionContext executionContext;

    @Inject
    public JPASearchRepository(JPAApi jpaApi, DatabaseExecutionContext executionContext) {
        this.jpaApi = jpaApi;
        this.executionContext = executionContext;
    }

    @Override
    public CompletionStage<List<SearchResult>> search(String userId, String query, int limit) {
        if (userId == null || userId.trim().isEmpty() || query == null || query.trim().isEmpty()) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        final String cleanQuery = query.trim();
        final int effectiveLimit = (limit <= 0 || limit > 50) ? 25 : limit;

        return supplyAsync(() -> wrap(em -> executeSearch(em, userId, cleanQuery, effectiveLimit)), executionContext);
    }

    private <T> T wrap(java.util.function.Function<EntityManager, T> function) {
        return jpaApi.withTransaction(function);
    }

    @SuppressWarnings("unchecked")
    private List<SearchResult> executeSearch(EntityManager em, String userId, String query, int limit) {
        try {
            String lowerQuery = query.toLowerCase();
            String[] rawTerms = lowerQuery.split("\\s+");
            List<String> terms = new ArrayList<>();
            for (String t : rawTerms) {
                if (!t.isEmpty()) {
                    terms.add(t);
                }
            }

            if (terms.isEmpty()) {
                return Collections.emptyList();
            }

            StringBuilder sql = new StringBuilder();
            sql.append("SELECT entity_type, entity_id, title, subtitle, target_url, dom_id, icon, badge ")
               .append("FROM app_search_view ")
               .append("WHERE user_id = :userId ");

            for (int i = 0; i < terms.size(); i++) {
                sql.append("AND search_text LIKE :term").append(i).append(" ");
            }

            sql.append("ORDER BY ")
               .append("CASE ")
               .append("  WHEN LOWER(title) = :exactQuery THEN 1 ")
               .append("  WHEN LOWER(title) LIKE :prefixQuery THEN 2 ")
               .append("  WHEN LOWER(title) LIKE :subQuery THEN 3 ")
               .append("  ELSE 4 ")
               .append("END, title ASC ")
               .append("LIMIT :limit");

            Query q = em.createNativeQuery(sql.toString());
            q.setParameter("userId", userId);
            q.setParameter("exactQuery", lowerQuery);
            q.setParameter("prefixQuery", lowerQuery + "%");
            q.setParameter("subQuery", "%" + lowerQuery + "%");

            for (int i = 0; i < terms.size(); i++) {
                q.setParameter("term" + i, "%" + terms.get(i) + "%");
            }
            q.setParameter("limit", limit);

            List<Object[]> rows = q.getResultList();
            List<SearchResult> results = new ArrayList<>(rows.size());

            for (Object[] row : rows) {
                String entityType = row[0] != null ? row[0].toString() : "";
                String entityId = row[1] != null ? row[1].toString() : "";
                String title = row[2] != null ? row[2].toString() : "";
                String subtitle = row[3] != null ? row[3].toString() : "";
                String targetUrl = row[4] != null ? row[4].toString() : "";
                String domId = row[5] != null ? row[5].toString() : "";
                String icon = row[6] != null ? row[6].toString() : "fa-search";
                String badge = row[7] != null ? row[7].toString() : "Item";

                results.add(new SearchResult(entityType, entityId, title, subtitle, targetUrl, domId, icon, badge));
            }

            return results;
        } catch (Exception e) {
            logger.error("Error executing app search query '{}' for user '{}': {}", query, userId, e.getMessage(), e);
            return Collections.emptyList();
        }
    }
}
