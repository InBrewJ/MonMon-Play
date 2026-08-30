package helpers;

import models.Account;
import models.Incoming;
import models.Outgoing;
import org.junit.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class ModelHelpersTest {

    @Test
    public void testGetActualPaydayDate_clampingAndWorkingDay() {
        // Feb 2021: Feb 28 is Sunday -> rolls back to Friday Feb 26
        LocalDate feb2021 = ModelHelpers.getActualPaydayDate(31, 2021, 2);
        assertThat(feb2021).isEqualTo(LocalDate.of(2021, 2, 26));

        // Feb 2026: Feb 28 is Saturday -> rolls back to Friday Feb 27
        LocalDate feb2026 = ModelHelpers.getActualPaydayDate(31, 2026, 2);
        assertThat(feb2026).isEqualTo(LocalDate.of(2026, 2, 27));

        // Jan 2026: Jan 31 is Saturday -> rolls back to Friday Jan 30
        LocalDate jan2026 = ModelHelpers.getActualPaydayDate(31, 2026, 1);
        assertThat(jan2026).isEqualTo(LocalDate.of(2026, 1, 30));

        // March 2026: March 31 is Tuesday -> remains March 31
        LocalDate mar2026 = ModelHelpers.getActualPaydayDate(31, 2026, 3);
        assertThat(mar2026).isEqualTo(LocalDate.of(2026, 3, 31));

        // April 2021: April 25 is Sunday -> rolls back to Friday April 23
        LocalDate apr2021 = ModelHelpers.getActualPaydayDate(25, 2021, 4);
        assertThat(apr2021).isEqualTo(LocalDate.of(2021, 4, 23));

        // Payday set to 28th in Feb 2021 (Feb 28 is Sunday) -> Friday Feb 26
        LocalDate feb28_2021 = ModelHelpers.getActualPaydayDate(28, 2021, 2);
        assertThat(feb28_2021).isEqualTo(LocalDate.of(2021, 2, 26));

        // August 2026: Nominal 31st is Monday (UK Summer Bank Holiday) -> rolls back over weekend (30, 29) to Friday Aug 28
        LocalDate aug2026 = ModelHelpers.getActualPaydayDate(31, 2026, 8);
        assertThat(aug2026).isEqualTo(LocalDate.of(2026, 8, 28));

        // Dec 2026: Nominal 25th is Christmas Day (Friday Bank Holiday) -> rolls back to Thursday Dec 24
        LocalDate dec2026 = ModelHelpers.getActualPaydayDate(25, 2026, 12);
        assertThat(dec2026).isEqualTo(LocalDate.of(2026, 12, 24));
    }

    @Test
    public void testFindLastAndNextPaydayDate() {
        // Payday is nominal 31.
        // On Feb 20, 2026: Feb payday is Feb 27 (Friday).
        LocalDate asOfBeforePayday = LocalDate.of(2026, 2, 20);
        LocalDate lastPayday = ModelHelpers.findLastPaydayDate(asOfBeforePayday, 31);
        LocalDate nextPayday = ModelHelpers.findNextPaydayDate(asOfBeforePayday, 31);

        // Last payday was Jan 30, 2026 (Friday)
        assertThat(lastPayday).isEqualTo(LocalDate.of(2026, 1, 30));
        // Next payday is Feb 27, 2026 (Friday)
        assertThat(nextPayday).isEqualTo(LocalDate.of(2026, 2, 27));

        // On Feb 27, 2026 (Payday itself):
        LocalDate asOfPayday = LocalDate.of(2026, 2, 27);
        assertThat(ModelHelpers.findLastPaydayDate(asOfPayday, 31)).isEqualTo(LocalDate.of(2026, 2, 27));
        assertThat(ModelHelpers.findNextPaydayDate(asOfPayday, 31)).isEqualTo(LocalDate.of(2026, 3, 31));
    }

    @Test
    public void testIncomingPaydayAdjustment() {
        Incoming salary = new Incoming();
        salary.setName("Salary");
        salary.setPayDay(true);
        salary.setIncomingMonthDay(28);

        // In Feb 2021 (Feb 28 is Sunday):
        LocalDate feb2021 = LocalDate.of(2021, 2, 1);
        assertThat(salary.getEffectiveIncomingMonthDay(feb2021)).isEqualTo(26);
        assertThat(salary.isAdjustedForMonth(feb2021)).isTrue();

        // In Feb 2026 (Feb 28 is Saturday):
        LocalDate feb2026 = LocalDate.of(2026, 2, 1);
        assertThat(salary.getEffectiveIncomingMonthDay(feb2026)).isEqualTo(27);
        assertThat(salary.isAdjustedForMonth(feb2026)).isTrue();

        // In Jan 2026 (Jan 28 is Wednesday):
        LocalDate jan2026 = LocalDate.of(2026, 1, 1);
        assertThat(salary.getEffectiveIncomingMonthDay(jan2026)).isEqualTo(28);
        assertThat(salary.isAdjustedForMonth(jan2026)).isFalse();
    }

    @Test
    public void testEffectiveOutgoingDay() {
        // In Feb 2026 (28 days)
        LocalDate febDate = LocalDate.of(2026, 2, 10);
        assertThat(ModelHelpers.getEffectiveOutgoingDay(31, febDate)).isEqualTo(28);
        assertThat(ModelHelpers.getEffectiveOutgoingDay(15, febDate)).isEqualTo(15);

        // In Jan 2026 (31 days)
        LocalDate janDate = LocalDate.of(2026, 1, 10);
        assertThat(ModelHelpers.getEffectiveOutgoingDay(31, janDate)).isEqualTo(31);
    }

    @Test
    public void testOutgoingAdjustmentMethods() {
        Outgoing o31 = new Outgoing();
        o31.setOutgoingDay(31);

        Outgoing o15 = new Outgoing();
        o15.setOutgoingDay(15);

        LocalDate feb = LocalDate.of(2026, 2, 1);
        LocalDate jan = LocalDate.of(2026, 1, 1);

        assertThat(o31.isAdjustedForMonth(feb)).isTrue();
        assertThat(o31.getEffectiveOutgoingDay(feb)).isEqualTo(28);

        assertThat(o31.isAdjustedForMonth(jan)).isFalse();
        assertThat(o31.getEffectiveOutgoingDay(jan)).isEqualTo(31);

        assertThat(o15.isAdjustedForMonth(feb)).isFalse();
        assertThat(o15.getEffectiveOutgoingDay(feb)).isEqualTo(15);
    }

    @Test
    public void testFindAlreadyPaidAndYetToPayWithClampedOutgoings() {
        Account dummy = new Account();
        dummy.setType(Account.AccountType.DEBIT);

        Outgoing o5 = new Outgoing();
        o5.setName("Early Outgoing");
        o5.setCost(50f);
        o5.setOutgoingDay(5);
        o5.setAccount(dummy);

        Outgoing oEnd = new Outgoing();
        oEnd.setName("End of Month Outgoing");
        oEnd.setCost(100f);
        oEnd.setOutgoingDay(31);
        oEnd.setAccount(dummy);

        List<Outgoing> outgoings = new ArrayList<>();
        outgoings.add(o5);
        outgoings.add(oEnd);

        // Payday is 31 (Jan 2026 payday is Jan 30; Feb 2026 payday is Feb 27).

        // 1. On Jan 30, 2026 (Payday): neither o5 nor oEnd has been paid yet.
        LocalDate asOfJan30 = LocalDate.of(2026, 1, 30);
        assertThat(ModelHelpers.findAlreadyPaid(outgoings, asOfJan30, 31)).isEmpty();
        assertThat(ModelHelpers.findYetToPay(outgoings, asOfJan30, 31)).containsExactlyInAnyOrder(o5, oEnd);

        // 2. On Jan 31, 2026: oEnd (day 31) has gone out on Jan 31. o5 (day 5) is still yet to pay.
        LocalDate asOfJan31 = LocalDate.of(2026, 1, 31);
        assertThat(ModelHelpers.findAlreadyPaid(outgoings, asOfJan31, 31)).containsExactly(oEnd);
        assertThat(ModelHelpers.findYetToPay(outgoings, asOfJan31, 31)).containsExactly(o5);

        // 3. On Feb 10, 2026: both oEnd (paid Jan 31) and o5 (paid Feb 5) have gone out in this pay period.
        LocalDate asOfFeb10 = LocalDate.of(2026, 2, 10);
        assertThat(ModelHelpers.findAlreadyPaid(outgoings, asOfFeb10, 31)).containsExactlyInAnyOrder(o5, oEnd);
        assertThat(ModelHelpers.findYetToPay(outgoings, asOfFeb10, 31)).isEmpty();

        // 4. On Feb 27, 2026 (New Payday): fresh cycle starts, both are pending again for the new cycle.
        LocalDate asOfFeb27 = LocalDate.of(2026, 2, 27);
        assertThat(ModelHelpers.findAlreadyPaid(outgoings, asOfFeb27, 31)).isEmpty();
        assertThat(ModelHelpers.findYetToPay(outgoings, asOfFeb27, 31)).containsExactlyInAnyOrder(o5, oEnd);

        // 5. On Feb 28, 2026: in Feb (28 days), oEnd (day 31) is clamped to Feb 28 and is paid.
        LocalDate asOfFeb28 = LocalDate.of(2026, 2, 28);
        assertThat(ModelHelpers.findAlreadyPaid(outgoings, asOfFeb28, 31)).containsExactly(oEnd);
        assertThat(ModelHelpers.findYetToPay(outgoings, asOfFeb28, 31)).containsExactly(o5);
    }
}
