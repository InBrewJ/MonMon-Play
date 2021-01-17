package helpers;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class MathHelpers {
    public static Float round2(Float val) {
        if (Float.isNaN(val)) {
            return 0.0f;
        }
        return new BigDecimal(val).setScale(2, RoundingMode.HALF_UP).floatValue();
    }
    public static Double round2(Double val) {
        if (Double.isNaN(val)) {
            return 0.0d;
        }
        return new BigDecimal(val).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
