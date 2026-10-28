package models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.type.TypeReference;
import play.libs.Json;

import javax.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "custom_spending_plan", indexes = @Index(columnList = "userId"))
public class CustomSpendingPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @Column(name = "userId", nullable = false)
    private String userId;

    private String name = "Custom Plan";

    @Column(columnDefinition = "TEXT")
    private String sectionsJson = "[]";

    private boolean archived = false;

    private Long createdAt;

    private Long lastUpdated;

    public CustomSpendingPlan() {
    }

    public CustomSpendingPlan(String userId, String name, List<SpendingPlanSection> sections) {
        this.userId = userId;
        this.name = name != null && !name.trim().isEmpty() ? name.trim() : "Custom Plan";
        setSections(sections);
        this.archived = false;
        long now = System.currentTimeMillis();
        this.createdAt = now;
        this.lastUpdated = now;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSectionsJson() {
        return sectionsJson != null ? sectionsJson : "[]";
    }

    public void setSectionsJson(String sectionsJson) {
        this.sectionsJson = sectionsJson != null ? sectionsJson : "[]";
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(Long lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    @JsonIgnore
    public List<SpendingPlanSection> getSections() {
        if (sectionsJson == null || sectionsJson.trim().isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return Json.mapper().readValue(sectionsJson, new TypeReference<List<SpendingPlanSection>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public void setSections(List<SpendingPlanSection> sections) {
        try {
            this.sectionsJson = Json.mapper().writeValueAsString(sections != null ? sections : new ArrayList<>());
        } catch (Exception e) {
            this.sectionsJson = "[]";
        }
    }

    @JsonIgnore
    public Float getTotalSectionsAmount() {
        float sum = 0f;
        for (SpendingPlanSection s : getSections()) {
            if (s.getAmount() != null) {
                sum += s.getAmount();
            }
        }
        return helpers.MathHelpers.round2(sum);
    }
}
