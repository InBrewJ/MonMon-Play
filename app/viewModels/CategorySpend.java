package viewModels;

public class CategorySpend {
    private final String category;
    private final long count;
    private final double totalSpent;
    private final double averageSpent;
    private final double percentage;

    public CategorySpend(String category, long count, double totalSpent, double averageSpent, double percentage) {
        this.category = category;
        this.count = count;
        this.totalSpent = totalSpent;
        this.averageSpent = averageSpent;
        this.percentage = percentage;
    }

    public String getCategory() {
        return category;
    }

    public long getCount() {
        return count;
    }

    public double getTotalSpent() {
        return totalSpent;
    }

    public double getAverageSpent() {
        return averageSpent;
    }

    public double getPercentage() {
        return percentage;
    }

    public String getFormattedCategory() {
        if (category == null || category.trim().isEmpty()) {
            return "General";
        }
        String clean = category.replace('_', ' ').replace('-', ' ').trim();
        String[] words = clean.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (!w.isEmpty()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(Character.toUpperCase(w.charAt(0)));
                if (w.length() > 1) {
                    sb.append(w.substring(1).toLowerCase());
                }
            }
        }
        return sb.toString();
    }
}
