package com.nudroidlabs.tvadapter;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/** Fits an existing Android View hierarchy and sends D-pad input to its activity. */
public final class TvAdapter extends FrameLayout {
    private static final float MIN_REMOTE_SCALE = 0.85f;
    private static final float DEFAULT_REMOTE_SCALE = 1.2f;
    private static final float MAX_REMOTE_SCALE = 2f;

    private final Activity activity;
    private View lastContentFocus;

    /** The host supplies the existing TV content size in pixels. */
    public TvAdapter(Activity activity, View content, int widthPx, int heightPx) {
        super(activity);
        if (widthPx <= 0 || heightPx <= 0) {
            throw new IllegalArgumentException("TV content dimensions must be positive.");
        }
        this.activity = activity;
        setBackgroundColor(Color.BLACK);
        setOnApplyWindowInsetsListener((view, insets) -> {
            setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });

        FrameLayout stage = new FrameLayout(activity);
        addView(stage, new FrameLayout.LayoutParams(-1, -1));
        stage.addView(new TvViewport(activity, content, widthPx, heightPx), new FrameLayout.LayoutParams(-1, -1));
        stage.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
            for (View view = newFocus; view != null; ) {
                if (view == content) {
                    lastContentFocus = newFocus;
                    break;
                }
                view = view.getParent() instanceof View ? (View) view.getParent() : null;
            }
        });
        addRemote(stage, content);
    }

    public static boolean isTelevision(Context context) {
        int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_TYPE_MASK;
        PackageManager packages = context.getPackageManager();
        return mode == Configuration.UI_MODE_TYPE_TELEVISION
                || packages.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                || packages.hasSystemFeature(PackageManager.FEATURE_TELEVISION);
    }

    private void addRemote(FrameLayout stage, View content) {
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(6), dp(4), dp(6), dp(6));
        panel.setBackground(rounded(0xf223272e, 13));
        panel.setElevation(dp(8));
        panel.setClickable(true);

        LinearLayout header = new LinearLayout(activity);
        Button grip = remoteButton("⠿", "Drag remote");
        header.addView(grip, new LinearLayout.LayoutParams(0, dp(32), 1f));
        Button close = remoteButton("×", "Close preview");
        header.addView(close, new LinearLayout.LayoutParams(dp(34), dp(32)));
        close.setOnClickListener(v -> activity.finish());
        panel.addView(header);

        LinearLayout up = addRemoteRow(panel);
        addSpacer(up);
        addRemoteKey(up, content, "↑", "Up", KeyEvent.KEYCODE_DPAD_UP);
        addSpacer(up);

        LinearLayout middle = addRemoteRow(panel);
        addRemoteKey(middle, content, "←", "Left", KeyEvent.KEYCODE_DPAD_LEFT);
        addRemoteKey(middle, content, "OK", "OK", KeyEvent.KEYCODE_DPAD_CENTER);
        addRemoteKey(middle, content, "→", "Right", KeyEvent.KEYCODE_DPAD_RIGHT);

        LinearLayout bottom = addRemoteRow(panel);
        addSpacer(bottom);
        addRemoteKey(bottom, content, "↓", "Down", KeyEvent.KEYCODE_DPAD_DOWN);
        addSpacer(bottom);

        LinearLayout footer = addRemoteRow(panel);
        addRemoteKey(footer, content, "Back", "Back", KeyEvent.KEYCODE_BACK);
        Button resize = remoteButton("↘", "Drag to resize remote");
        LinearLayout.LayoutParams resizeParams = new LinearLayout.LayoutParams(dp(34), -1);
        resizeParams.setMargins(dp(2), dp(2), dp(2), dp(2));
        footer.addView(resize, resizeParams);

        panel.setPivotX(0);
        panel.setPivotY(0);
        panel.setVisibility(View.INVISIBLE);
        stage.addView(panel, new FrameLayout.LayoutParams(dp(144),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT));
        installRemoteGesture(stage, panel, grip, false);
        installRemoteGesture(stage, panel, resize, true);
        stage.addOnLayoutChangeListener((view, left, top, right, stageBottom, oldLeft, oldTop,
                oldRight, oldBottom) -> {
            boolean firstLayout = panel.getVisibility() == View.INVISIBLE;
            float x = firstLayout ? stage.getWidth() - panel.getWidth()
                    * DEFAULT_REMOTE_SCALE - dp(16) : panel.getX();
            float y = firstLayout ? stage.getHeight() - panel.getHeight()
                    * DEFAULT_REMOTE_SCALE - dp(16) : panel.getY();
            placeRemote(stage, panel, x, y,
                    firstLayout ? DEFAULT_REMOTE_SCALE : panel.getScaleX());
            panel.setVisibility(View.VISIBLE);
        });
    }

    private void installRemoteGesture(FrameLayout stage, View panel, Button handle,
            boolean resize) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            private float touchX;
            private float touchY;
            private float startX;
            private float startY;
            private float startScale;
            private boolean active;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        touchX = event.getRawX();
                        touchY = event.getRawY();
                        startX = panel.getX();
                        startY = panel.getY();
                        startScale = panel.getScaleX();
                        active = true;
                        handle.setPressed(true);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!active) return true;
                        float dx = event.getRawX() - touchX;
                        float dy = event.getRawY() - touchY;
                        if (resize) {
                            float width = panel.getWidth();
                            float height = panel.getHeight();
                            // Project the drag onto the corner diagonal to scale both axes evenly.
                            float delta = (width * dx + height * dy)
                                    / (width * width + height * height);
                            placeRemote(stage, panel, startX, startY, startScale + delta);
                        } else {
                            placeRemote(stage, panel, startX + dx, startY + dy, startScale);
                        }
                        return true;
                    case MotionEvent.ACTION_POINTER_DOWN:
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        active = false;
                        handle.setPressed(false);
                        return true;
                    default:
                        return true;
                }
            }
        });
    }

    private void placeRemote(FrameLayout stage, View panel, float x, float y, float scale) {
        if (panel.getWidth() == 0 || panel.getHeight() == 0
                || stage.getWidth() == 0 || stage.getHeight() == 0) return;

        float fit = Math.min(stage.getWidth() / (float) panel.getWidth(),
                stage.getHeight() / (float) panel.getHeight());
        scale = Math.min(fit, Math.min(MAX_REMOTE_SCALE, Math.max(MIN_REMOTE_SCALE, scale)));
        panel.setScaleX(scale);
        panel.setScaleY(scale);
        panel.setX(Math.max(0, Math.min(x, stage.getWidth() - panel.getWidth() * scale)));
        panel.setY(Math.max(0, Math.min(y, stage.getHeight() - panel.getHeight() * scale)));
    }

    private LinearLayout addRemoteRow(LinearLayout panel) {
        LinearLayout row = new LinearLayout(activity);
        panel.addView(row, new LinearLayout.LayoutParams(-1, dp(40)));
        return row;
    }

    private void addSpacer(LinearLayout row) {
        row.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1f));
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private Button remoteButton(String label, String description) {
        Button button = new Button(activity);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setContentDescription(description);
        button.setFocusable(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(0, 0, 0, 0);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x55ffffff),
                rounded(0xff363e49, 7), null));
        return button;
    }

    private void addRemoteKey(LinearLayout row, View content, String label,
            String description, int code) {
        Button button = remoteButton(label, description);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1f);
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        row.addView(button, params);
        button.setOnClickListener(view -> sendRemoteKey(content, code));
    }

    private void sendRemoteKey(View content, int code) {
        if (code == KeyEvent.KEYCODE_BACK) {
            activity.onBackPressed();
            return;
        }

        View focused = content.findFocus();
        if (focused == null) focused = lastContentFocus;
        if (focused == null) focused = content.focusSearch(View.FOCUS_FORWARD);
        if (focused != null) focused.requestFocusFromTouch();

        // D-pad navigation can leave a text field when a focus target exists.
        if (activity.getCurrentFocus() instanceof EditText &&
                (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) &&
                moveFocus(code)) {
            return;
        }

        long now = SystemClock.uptimeMillis();
        boolean handled = activity.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_DPAD));
        if (!handled) moveFocus(code);
        activity.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_DPAD));
    }

    private boolean moveFocus(int code) {
        int direction;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: direction = View.FOCUS_LEFT; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: direction = View.FOCUS_RIGHT; break;
            case KeyEvent.KEYCODE_DPAD_UP: direction = View.FOCUS_UP; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: direction = View.FOCUS_DOWN; break;
            default: return false;
        }
        View focused = activity.getCurrentFocus();
        if (focused == null) return false;

        View next = focused.focusSearch(direction);
        return next != null && next.requestFocus(direction);
    }

    private static final class TvViewport extends FrameLayout {
        private final View content;

        private final int widthPx;
        private final int heightPx;

        TvViewport(Context context, View content, int widthPx, int heightPx) {
            super(context);
            this.content = content;
            this.widthPx = widthPx;
            this.heightPx = heightPx;
            content.setPivotX(0f);
            content.setPivotY(0f);
            addView(content, new FrameLayout.LayoutParams(widthPx, heightPx));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec),
                    MeasureSpec.getSize(heightMeasureSpec));
            content.measure(MeasureSpec.makeMeasureSpec(widthPx, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(heightPx, MeasureSpec.EXACTLY));
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            int width = right - left;
            int height = bottom - top;
            float scale = Math.min(width / (float) widthPx, height / (float) heightPx);
            int x = Math.round((width - widthPx * scale) / 2f);
            int y = Math.round((height - heightPx * scale) / 2f);
            content.setScaleX(scale);
            content.setScaleY(scale);
            content.layout(x, y, x + widthPx, y + heightPx);
        }
    }
}
