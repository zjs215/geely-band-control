package com.garage.bandcontrol;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.view.View;
import java.util.function.IntConsumer;

/** Configurable slot identities from 4.2.9/4.3.0. No inferred functional directions. */
final class LynkWidgets extends GalaxyWidgets {
    enum SlotState {
        READY(""), LOADING("loading"), UNKNOWN("unknown"), CONFIRMING("");

        final String wireValue;

        SlotState(String wireValue) { this.wireValue = wireValue; }
    }

    LynkWidgets(Context context, SharedPreferences prefs, Handler main, IntConsumer onChange) {
        super(context, prefs, main, onChange, BrandConfig.LYNKCO);
    }

    static Binding binding(WidgetTree.Button button) {
        CarAction action = actionForSlot(button.slot());
        return action == null ? null : new Binding(button.slot(), button.fingerprint(), action.id, "");
    }

    static CarAction actionForSlot(String slot) {
        return switch (slot) {
            case "fr_unlock" -> CarAction.LYNK_UNLOCK;
            case "fr_window" -> CarAction.LYNK_WINDOW;
            case "fr_kjck" -> CarAction.LYNK_KJCK;
            case "fr_ccqk" -> CarAction.LYNK_CCQK;
            default -> null;
        };
    }

    static SlotState slotState(WidgetTree tree, View root, String slot,
                               WidgetTree.Button button, boolean ownerMarked) {
        if (tree.confirmationVisible(root)) return SlotState.CONFIRMING;
        if (tree.loading(root) || tree.slotLoading(root, slot)) return SlotState.LOADING;
        if (button == null || (!ownerMarked && button.match() == null && button.lynkIcon() == null)) {
            return SlotState.UNKNOWN;
        }
        return SlotState.READY;
    }
}
