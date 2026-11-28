package com.mario.lynkband;

import com.mario.lynkband.transport.ControlCategory;
import org.junit.Test;
import static org.junit.Assert.*;

public class CardOperationTest {
    private final CardOperation operation = new CardOperation();
    private static final String SLOT = "card/vehicle/window";

    @Test public void openingNeedsTheSameSlotsSpinnerAndTargetState() {
        operation.start(SLOT, "车窗", "closed", 0);
        assertEquals("", operation.observe(SLOT, "closed", 1));
        assertEquals("", operation.observe("other", "loading", 2));
        assertEquals("车窗 · 开启中", operation.observe(SLOT, "loading", 3));
        assertEquals("", operation.observe(SLOT, "loading", 4));
        assertEquals("车窗 · 结果待核对", operation.observe(SLOT, "unknown", 5));
        assertEquals("", operation.observe("other", "open", 6));
        assertEquals("车窗 · 已完成", operation.observe(SLOT, "open", 7));
        assertFalse(operation.active());
    }

    @Test public void closingMayCompleteBetweenSpinnerSamples() {
        operation.start(SLOT, "车窗", "open", 0);
        assertEquals("车窗 · 关闭中", operation.observe(SLOT, "loading", 1));
        assertEquals("车窗 · 结果待核对", operation.observe(SLOT, "open", 2));
        assertEquals("车窗 · 结果待核对", operation.observe(SLOT, "closed", CardOperation.TIMEOUT));
        operation.start(SLOT, "车窗", "open", 100_000);
        assertEquals("车窗 · 已完成", operation.observe(SLOT, "closed", 100_001));
    }

    @Test public void unknownInitialDirectionCannotProduceACompletion() {
        for (String initial : new String[]{"unknown", "loading", ""}) {
            operation.start(SLOT, "车窗", initial, 0);
            assertFalse(operation.active());
            assertEquals("", operation.observe(SLOT, "open", 1));
        }
    }

    @Test public void wireStateUsesOnlyTheVisibleCardSpinner() throws Exception {
        for (boolean visible : new boolean[]{true, false}) {
            for (boolean spinner : new boolean[]{true, false}) {
                var slot = new CardContent.Slot("window", "signature", null, null, SLOT, spinner, visible);
                ControlItem control = ControlItem.resolve(ControlCategory.WINDOW, slot);
                var wire = control.wireAction(slot, true, true);
                assertEquals(visible && spinner ? "loading" : "unknown", wire.getString("controlState"));
                assertEquals(visible && spinner ? "车窗.加载" : "车窗.未知", wire.getString("displayLabel"));
                assertFalse(wire.getBoolean("enabled"));
                assertEquals("window", wire.getString("slot"));
                assertEquals("signature", wire.getString("key"));
                assertTrue(control.action().isEmpty());
            }
        }
    }
}
