package helpers;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class MathHelpers {
    public static Float round2(Float val) {
        return new BigDecimal(val).setScale(2, RoundingMode.HALF_UP).floatValue();
    }
    public static Double round2(Double val) {
        return new BigDecimal(val).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
