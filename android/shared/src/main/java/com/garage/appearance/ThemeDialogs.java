package com.garage.appearance;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

public final class ThemeDialogs {
    private static final ThemeStyles.Palette CLASSIC = new ThemeStyles.Palette(
            0xff10161b, 0xff1c2930, 0xff2c3d46, 0xff203d38,
            0xffeff6f7, 0xffb8c9ce, 0xff9ae8ce, 0xff40515b);

    private final Context owner;
    private final ThemeStyles styles;

    public ThemeDialogs(Context owner, ThemeStyles styles) {
        this.owner = owner;
        this.styles = styles;
    }

    public Context context() {
        return styles.dialogContext(owner);
    }

    public AlertDialog.Builder builder() {
        return new AlertDialog.Builder(context()) {
            @Override public AlertDialog.Builder setTitle(CharSequence title) {
                super.setTitle(title);
                return setCustomTitle(heading(getContext(), title));
            }

            @Override public AlertDialog show() {
                AlertDialog dialog = super.show();
                decorate(dialog);
                return dialog;
            }
        };
    }

    TextView heading(Context context, CharSequence title) {
        TextView heading = new TextView(context);
        heading.setText(title);
        heading.setTextColor(palette().ink());
        heading.setTextSize(22);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setAccessibilityHeading(true);
        heading.setPadding(dp(24), dp(22), dp(24), dp(12));
        return heading;
    }

    void decorate(AlertDialog dialog) {
        ThemeStyles.Palette colors = palette();
        int surface = colors == ThemeStyles.DAY ? 0xfff6fafd
                : colors == ThemeStyles.NIGHT ? 0xff1e2c40 : colors.surface();
        var window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(rounded(surface, 24));
            window.setDimAmount(0.55f);
            int screenWidth = owner.getResources().getConfiguration().screenWidthDp;
            window.setLayout(dp(Math.min(420, Math.max(1, screenWidth - 36))), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        TextView message = dialog.findViewById(android.R.id.message);
        if (message != null) {
            message.setTextSize(15);
            message.setTextColor(colors.muted());
            message.setLineSpacing(dp(4), 1f);
        }
        ListView list = dialog.getListView();
        if (list != null) {
            list.setDivider(new ColorDrawable((colors.border() & 0x00ffffff) | 0x44000000));
            list.setDividerHeight(dp(1));
        }
        styleAction(dialog.getButton(AlertDialog.BUTTON_POSITIVE), true, false);
        styleAction(dialog.getButton(AlertDialog.BUTTON_NEGATIVE), false, false);
        styleAction(dialog.getButton(AlertDialog.BUTTON_NEUTRAL), false, true);
    }

    public void styleInput(EditText input) {
        ThemeStyles.Palette colors = palette();
        input.setTextColor(colors.ink());
        input.setHintTextColor(colors.muted());
        input.setBackground(rounded(colors.control(), 14));
        input.setTextSize(16);
        input.setMinHeight(dp(52));
        input.setPadding(dp(14), dp(11), dp(14), dp(11));
    }

    void styleField(Button button) {
        ThemeStyles.Palette colors = palette();
        button.setTextColor(colors.ink());
        button.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START);
        button.setTextSize(15);
        button.setMinHeight(dp(49));
        button.setMinimumHeight(dp(49));
        button.setStateListAnimator(null);
        button.setBackground(ripple(colors.control(), colors.accent(), 12));
        button.setPadding(dp(14), dp(10), dp(14), dp(10));
    }

    private void styleAction(Button button, boolean primary, boolean quiet) {
        if (button == null || button.getVisibility() != Button.VISIBLE) return;
        ThemeStyles.Palette colors = palette();
        int fill = primary ? colors.accent() : quiet ? Color.TRANSPARENT : colors.control();
        int ink = primary ? colors == ThemeStyles.DAY ? Color.WHITE : colors.background() : colors.ink();
        button.setTextColor(ink);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setLetterSpacing(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(44));
        button.setMinimumHeight(dp(44));
        button.setStateListAnimator(null);
        button.setBackground(ripple(fill, primary ? ink : colors.accent(), 12));
        button.setPadding(dp(11), dp(6), dp(11), dp(6));
        if (button.getLayoutParams() instanceof ViewGroup.MarginLayoutParams margins) {
            margins.setMarginStart(dp(5));
            button.setLayoutParams(margins);
        }
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private RippleDrawable ripple(int fill, int tint, int radius) {
        return new RippleDrawable(ColorStateList.valueOf((tint & 0x00ffffff) | 0x26000000),
                rounded(fill, radius), rounded(Color.WHITE, radius));
    }

    private ThemeStyles.Palette palette() {
        ThemeStyles.Palette palette = styles.currentPalette();
        return palette == null ? CLASSIC : palette;
    }

    private int dp(int value) {
        return Math.round(value * owner.getResources().getDisplayMetrics().density);
    }
}
