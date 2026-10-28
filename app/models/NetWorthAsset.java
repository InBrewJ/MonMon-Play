package models;

import javax.persistence.*;

@Entity
@Table(name = "net_worth_asset", indexes = @Index(columnList = "userId"))
public class NetWorthAsset {

    public enum AssetCategory {
        PROPERTY,   // e.g. flat, house, land
        VEHICLE,    // e.g. car, motorcycle, van
        VALUABLE,   // e.g. jewellery, watches, art
        INVESTMENT, // e.g. private equity, crypto, metals
        OTHER       // other tangible or capital assets
    }

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    public Long id;

    @Column(name = "userId", nullable = false)
    public String userId;

    @Column(nullable = false)
    public String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public AssetCategory category = AssetCategory.OTHER;

    @Column(name = "asset_value", nullable = false)
    public Double value = 0.0;

    @Column(nullable = true)
    public String notes;

    public boolean archived = false;

    @Column(name = "created_at")
    public Long createdAt;

    @Column(name = "updated_at")
    public Long updatedAt;

    @Column(name = "deleted_at")
    public Long deletedAt;

    public NetWorthAsset() {
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = System.currentTimeMillis();
    }

    public NetWorthAsset(String userId, String name, AssetCategory category, Double value, String notes) {
        this();
        this.userId = userId;
        this.name = name;
        this.category = category != null ? category : AssetCategory.OTHER;
        this.value = value != null ? value : 0.0;
        this.notes = notes;
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

    public AssetCategory getCategory() {
        return category;
    }

    public void setCategory(AssetCategory category) {
        this.category = category;
    }

    public Double getValue() {
        return value != null ? value : 0.0;
    }

    public void setValue(Double value) {
        this.value = value != null ? value : 0.0;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
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

    public Long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Long deletedAt) {
        this.deletedAt = deletedAt;
    }

    public String getCategoryHumanReadable() {
        if (category == null) return "Other";
        switch (category) {
            case PROPERTY: return "Property (Flat/House)";
            case VEHICLE: return "Vehicle (Car/Van)";
            case VALUABLE: return "Valuable (Jewellery/Art)";
            case INVESTMENT: return "Investment/Crypto";
            default: return "Other Asset";
        }
    }

    public String getCategoryIcon() {
        if (category == null) return "fa-box";
        switch (category) {
            case PROPERTY: return "fa-home";
            case VEHICLE: return "fa-car";
            case VALUABLE: return "fa-gem";
            case INVESTMENT: return "fa-chart-line";
            default: return "fa-cube";
        }
    }
}
