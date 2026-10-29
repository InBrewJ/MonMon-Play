package viewModels;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Unified search result item across all application entities and primitives.
 */
public class SearchResult {

    @JsonProperty("entityType")
    private String entityType;

    @JsonProperty("entityId")
    private String entityId;

    @JsonProperty("title")
    private String title;

    @JsonProperty("subtitle")
    private String subtitle;

    @JsonProperty("targetUrl")
    private String targetUrl;

    @JsonProperty("domId")
    private String domId;

    @JsonProperty("icon")
    private String icon;

    @JsonProperty("badge")
    private String badge;

    public SearchResult() {
    }

    public SearchResult(String entityType, String entityId, String title, String subtitle, String targetUrl, String domId, String icon, String badge) {
        this.entityType = entityType;
        this.entityId = entityId;
        this.title = title;
        this.subtitle = subtitle;
        this.targetUrl = targetUrl;
        this.domId = domId;
        this.icon = icon;
        this.badge = badge;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    public void setEntityId(String entityId) {
        this.entityId = entityId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSubtitle() {
        return subtitle;
    }

    public void setSubtitle(String subtitle) {
        this.subtitle = subtitle;
    }

    public String getTargetUrl() {
        return targetUrl;
    }

    public void setTargetUrl(String targetUrl) {
        this.targetUrl = targetUrl;
    }

    public String getDomId() {
        return domId;
    }

    public void setDomId(String domId) {
        this.domId = domId;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public String getBadge() {
        return badge;
    }

    public void setBadge(String badge) {
        this.badge = badge;
    }
}
