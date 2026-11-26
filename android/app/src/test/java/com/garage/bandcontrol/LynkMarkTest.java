package com.garage.bandcontrol;
import org.junit.Test;
import static org.junit.Assert.*;
public final class LynkMarkTest {
    @Test public void ownerEvidenceBindsSlotSignatureAndDescription() {
        LynkMark mark = new LynkMark("fr_window", "synthetic-a", "fixture", "车窗微开", "open");
        assertTrue(mark.matches("fr_window", "synthetic-a", "fixture"));
        assertFalse(mark.matches("fr_kjck", "synthetic-a", "fixture"));
        assertFalse(mark.matches("fr_window", "synthetic-b", "fixture"));
        assertFalse(mark.matches("fr_window", "synthetic-a", "changed"));
    }
    @Test public void signatureChangeInvalidatesPreparedTicket() {
        Confirmation confirmations = new Confirmation();
        String token = confirmations.prepare("fixture", CarAction.LYNK_WINDOW, "signature-a", 0);
        assertFalse(confirmations.consume("fixture", CarAction.LYNK_WINDOW, token, "signature-b", 1));
        assertFalse(confirmations.consume("fixture", CarAction.LYNK_WINDOW, token, "signature-a", 2));
    }
}
