package viewModels;

import java.util.Collections;
import java.util.List;

import static helpers.MathHelpers.round2;

public class HistoricSpentViewModel {
    private final List<HistoricCycleSpent> cycles;
    private final HistoricCycleSpent currentCycle;
    private final Double averageMonthlySpend;
    private final HistoricCycleSpent highestSpendCycle;
    private final HistoricCycleSpent lowestSpendCycle;

    public HistoricSpentViewModel(List<HistoricCycleSpent> cycles) {
        this.cycles = cycles != null ? cycles : Collections.emptyList();
        HistoricCycleSpent curr = null;
        double sum = 0.0;
        int completedCount = 0;
        HistoricCycleSpent highest = null;
        HistoricCycleSpent lowest = null;

        for (HistoricCycleSpent c : this.cycles) {
            if (c.isCurrentCycle()) {
                curr = c;
            } else {
                sum += c.getTotalSpent();
                completedCount++;
                if (highest == null || c.getTotalSpent() > highest.getTotalSpent()) {
                    highest = c;
                }
                if (lowest == null || c.getTotalSpent() < lowest.getTotalSpent()) {
                    lowest = c;
                }
            }
        }

        this.currentCycle = curr;
        this.averageMonthlySpend = completedCount > 0 ? round2(sum / completedCount) : (curr != null ? curr.getTotalSpent() : 0.0);
        this.highestSpendCycle = highest;
        this.lowestSpendCycle = lowest;
    }

    public List<HistoricCycleSpent> getCycles() {
        return cycles;
    }

    public HistoricCycleSpent getCurrentCycle() {
        return currentCycle;
    }

    public Double getAverageMonthlySpend() {
        return averageMonthlySpend;
    }

    public HistoricCycleSpent getHighestSpendCycle() {
        return highestSpendCycle;
    }

    public HistoricCycleSpent getLowestSpendCycle() {
        return lowestSpendCycle;
    }

    public boolean hasHistory() {
        return !cycles.isEmpty();
    }
}
