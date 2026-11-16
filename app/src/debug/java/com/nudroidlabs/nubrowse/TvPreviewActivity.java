package com.nudroidlabs.nubrowse;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

public final class TvPreviewActivity extends MainActivity {
    private static final int TV_WIDTH = 1920;
    private static final int TV_HEIGHT = 1080;
    private static final int TV_DENSITY_DPI = 240;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Configuration tv = new Configuration();
        tv.densityDpi = TV_DENSITY_DPI;
        tv.fontScale = 1f;
        tv.orientation = Configuration.ORIENTATION_LANDSCAPE;
        tv.screenWidthDp = TV_WIDTH * 160 / TV_DENSITY_DPI;
        tv.screenHeightDp = TV_HEIGHT * 160 / TV_DENSITY_DPI;
        tv.smallestScreenWidthDp = tv.screenHeightDp;
        applyOverrideConfiguration(tv);
    }

    @Override
    public void setContentView(View browserContent) {
        LinearLayout host = new LinearLayout(this);
        host.setOrientation(LinearLayout.VERTICAL);
        host.setBackgroundColor(Color.BLACK);
        host.setOnApplyWindowInsetsListener((view, insets) -> {
            host.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });

        host.addView(new TvViewport(this, browserContent), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout remote = new LinearLayout(this);
        remote.setGravity(Gravity.CENTER);
        remote.setBackgroundColor(0xff151719);
        addRemoteKey(remote, "←", "Left", KeyEvent.KEYCODE_DPAD_LEFT);
        addRemoteKey(remote, "↑", "Up", KeyEvent.KEYCODE_DPAD_UP);
        addRemoteKey(remote, "↓", "Down", KeyEvent.KEYCODE_DPAD_DOWN);
        addRemoteKey(remote, "→", "Right", KeyEvent.KEYCODE_DPAD_RIGHT);
        addRemoteKey(remote, "OK", "OK", KeyEvent.KEYCODE_DPAD_CENTER);
        addRemoteKey(remote, "Back", "Back", KeyEvent.KEYCODE_BACK);
        Button close = addRemoteButton(remote, "✕", "Close preview");
        close.setOnClickListener(view -> finish());
        host.addView(remote, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        super.setContentView(host);
        host.requestApplyInsets();
    }

    private Button addRemoteButton(LinearLayout row, String label, String description) {
        Button button = new Button(this);
        button.setText(label);
        button.setContentDescription(description);
        button.setFocusable(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        row.addView(button, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return button;
    }

    private void addRemoteKey(LinearLayout row, String label, String description, int code) {
        Button button = addRemoteButton(row, label, description);
        button.setOnClickListener(view -> {
            if (code == KeyEvent.KEYCODE_BACK) {
                onBackPressed();
                return;
            }
            View focused = getCurrentFocus();
            if (focused != null) focused.requestFocusFromTouch();

            long now = SystemClock.uptimeMillis();
            boolean handled = dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                    code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_DPAD));
            if (!handled) moveFocus(code);
            dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_DPAD));
        });
    }

    private void moveFocus(int code) {
        int direction;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: direction = View.FOCUS_LEFT; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: direction = View.FOCUS_RIGHT; break;
            case KeyEvent.KEYCODE_DPAD_UP: direction = View.FOCUS_UP; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: direction = View.FOCUS_DOWN; break;
            default: return;
        }
        View focused = getCurrentFocus();
        if (focused == null) return;

        View next = focused.focusSearch(direction);
        if (next != null) next.requestFocus(direction);
    }

    private static final class TvViewport extends FrameLayout {
        private final View content;

        TvViewport(Context context, View content) {
            super(context);
            this.content = content;
            content.setPivotX(0f);
            content.setPivotY(0f);
            addView(content, new FrameLayout.LayoutParams(TV_WIDTH, TV_HEIGHT));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec),
                    MeasureSpec.getSize(heightMeasureSpec));
            content.measure(MeasureSpec.makeMeasureSpec(TV_WIDTH, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(TV_HEIGHT, MeasureSpec.EXACTLY));
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            int width = right - left;
            int height = bottom - top;
            float scale = Math.min(width / (float) TV_WIDTH, height / (float) TV_HEIGHT);
            int x = Math.round((width - TV_WIDTH * scale) / 2f);
            int y = Math.round((height - TV_HEIGHT * scale) / 2f);
            content.setScaleX(scale);
            content.setScaleY(scale);
            content.layout(x, y, x + TV_WIDTH, y + TV_HEIGHT);
        }
    }
}
