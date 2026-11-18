package com.nudroidlabs.tvadapter;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.SystemClock;
import android.view.FocusFinder;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.WeakHashMap;

/** Fits an existing TV layout and adds local remote and touch controls. */
public final class TvAdapter extends FrameLayout {
    private static final float MIN_SCALE = 0.75f;
    private static final float MAX_SCALE = 1.65f;
    private static final int PANEL_FILL = 0xf5192439;
    private static final int BUTTON_FILL = 0xff293853;
    private static final int PAD_FILL = 0xff202e48;
    private static final int NO_POSITION = -1;

    // A host can create a new adapter per screen. Keep only the remote's settings.
    private static final WeakHashMap<Activity, RemoteState> REMOTE_STATES = new WeakHashMap<>();

    private static final class RemoteState {
        boolean touchMode;
        float xFraction = NO_POSITION;
        float yFraction = NO_POSITION;
        float scale = 1f;
    }

    private final Activity activity;
    private final Context controlContext;
    private final View content;
    private final RemoteState remote;
    private final FrameLayout stage;
    private final View touchBlocker;
    private final FrameLayout panel;
    private final View collapsed;
    private View lastContentFocus;
    private final ViewTreeObserver.OnGlobalFocusChangeListener focusListener =
            (oldFocus, newFocus) -> {
                if (belongsToContent(newFocus)) lastContentFocus = newFocus;
            };

    /** The host supplies the TV content size in pixels. */
    public TvAdapter(Activity activity, View content, int widthPx, int heightPx) {
        this(activity, activity, content, widthPx, heightPx);
    }

    public TvAdapter(Activity activity, View content, TvPreviewDisplay display) {
        this(activity, activity.getBaseContext(), content, display.widthPx(), display.heightPx());
    }

    private TvAdapter(Activity activity, Context controlContext, View content,
            int widthPx, int heightPx) {
        super(controlContext);
        if (widthPx <= 0 || heightPx <= 0) {
            throw new IllegalArgumentException("TV content dimensions must be positive.");
        }
        this.activity = activity;
        this.controlContext = controlContext;
        this.content = content;
        RemoteState saved = REMOTE_STATES.get(activity);
        if (saved == null) {
            saved = new RemoteState();
            REMOTE_STATES.put(activity, saved);
        }
        remote = saved;

        setBackgroundColor(Color.BLACK);
        setOnApplyWindowInsetsListener((view, insets) -> {
            setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        stage = new FrameLayout(controlContext);
        addView(stage, new LayoutParams(-1, -1));
        stage.addView(new TvViewport(controlContext, content, widthPx, heightPx),
                new LayoutParams(-1, -1));

        touchBlocker = new View(controlContext);
        touchBlocker.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        touchBlocker.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) restoreFocus();
            return true;
        });
        stage.addView(touchBlocker, new LayoutParams(-1, -1));

        panel = createPanel();
        panel.setElevation(dp(8));
        panel.setPivotX(0);
        panel.setPivotY(0);
        stage.addView(panel, new LayoutParams(dp(164), dp(222), Gravity.TOP | Gravity.LEFT));
        installDrag(panel, false, false);

        collapsed = icon("remote", "Switch to D-pad mode", BUTTON_FILL, 14);
        collapsed.setElevation(dp(8));
        collapsed.setOnClickListener(view -> setTouchMode(false));
        stage.addView(collapsed, new LayoutParams(dp(46), dp(46), Gravity.TOP | Gravity.LEFT));
        installDrag(collapsed, false, true);

        stage.addOnLayoutChangeListener((view, l, t, r, b, oldL, oldT, oldR, oldB) ->
                positionRemote());
        panel.addOnLayoutChangeListener((view, l, t, r, b, oldL, oldT, oldR, oldB) ->
                positionRemote());
        collapsed.addOnLayoutChangeListener((view, l, t, r, b, oldL, oldT, oldR, oldB) ->
                positionRemote());
        setTouchMode(remote.touchMode);
    }

    public static boolean isTelevision(Context context) {
        int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_TYPE_MASK;
        PackageManager packages = context.getPackageManager();
        return mode == Configuration.UI_MODE_TYPE_TELEVISION
                || packages.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                || packages.hasSystemFeature(PackageManager.FEATURE_TELEVISION);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnGlobalFocusChangeListener(focusListener);
        if (!remote.touchMode) post(this::ensureDpadFocus);
    }

    @Override protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnGlobalFocusChangeListener(focusListener);
        super.onDetachedFromWindow();
    }

    private void setTouchMode(boolean enabled) {
        remote.touchMode = enabled;
        touchBlocker.setVisibility(enabled ? View.GONE : View.VISIBLE);
        // INVISIBLE keeps both controls measured, so the icon can share the panel's anchor.
        panel.setVisibility(enabled ? View.INVISIBLE : View.VISIBLE);
        collapsed.setVisibility(enabled ? View.VISIBLE : View.INVISIBLE);
        positionRemote();
        if (!enabled) ensureDpadFocus();
    }

    private void ensureDpadFocus() {
        if (!remote.touchMode && isAttachedToWindow()) restoreFocus();
    }

    private FrameLayout createPanel() {
        FrameLayout remotePanel = new FrameLayout(controlContext);
        GradientDrawable background = rounded(PANEL_FILL, 18);
        background.setStroke(dp(1), 0xff3a4b68);
        remotePanel.setBackground(background);

        View touch = icon("touch", "Switch to Touch mode", BUTTON_FILL, 16);
        touch.setOnClickListener(view -> setTouchMode(true));
        place(remotePanel, touch, 48, 36, 58, 8);

        FrameLayout pad = new FrameLayout(controlContext);
        pad.setBackground(rounded(PAD_FILL, 66));
        place(remotePanel, pad, 132, 132, 16, 48);
        addArrow(pad, "up", "Up", KeyEvent.KEYCODE_DPAD_UP, 40, 0, 52, 42);
        addArrow(pad, "left", "Left", KeyEvent.KEYCODE_DPAD_LEFT, 0, 40, 42, 52);
        addArrow(pad, "right", "Right", KeyEvent.KEYCODE_DPAD_RIGHT, 90, 40, 42, 52);
        addArrow(pad, "down", "Down", KeyEvent.KEYCODE_DPAD_DOWN, 40, 90, 52, 42);
        TextView ok = label("OK", 0xff131d31, 25);
        ok.setOnClickListener(view -> sendRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER));
        place(pad, ok, 48, 48, 42, 42);

        TextView back = label("Back", BUTTON_FILL, 14);
        back.setOnClickListener(view -> sendRemoteKey(KeyEvent.KEYCODE_BACK));
        place(remotePanel, back, 101, 34, 8, 181);

        View resize = icon("resize", "Drag to resize remote", BUTTON_FILL, 11);
        place(remotePanel, resize, 39, 34, 117, 181);
        installDrag(resize, true, false);
        return remotePanel;
    }

    private void place(FrameLayout parent, View view, int width, int height, int x, int y) {
        LayoutParams params = new LayoutParams(dp(width), dp(height), Gravity.TOP | Gravity.LEFT);
        params.leftMargin = dp(x);
        params.topMargin = dp(y);
        parent.addView(view, params);
    }

    private void addArrow(FrameLayout pad, String shape, String description,
            int key, int x, int y, int width, int height) {
        View control = icon(shape, description, Color.TRANSPARENT, 19);
        control.setOnClickListener(view -> sendRemoteKey(key));
        place(pad, control, width, height, x, y);
    }

    private TextView label(String text, int fill, int radius) {
        TextView view = new TextView(controlContext);
        view.setText(text);
        view.setTextSize(15);
        view.setTextColor(Color.WHITE);
        view.setGravity(Gravity.CENTER);
        view.setIncludeFontPadding(false);
        styleControl(view, fill, radius);
        return view;
    }

    private View icon(String shape, String description, int fill, int radius) {
        View view = new RemoteIcon(controlContext, shape);
        view.setContentDescription(description);
        styleControl(view, fill, radius);
        return view;
    }

    private void styleControl(View view, int fill, int radius) {
        view.setFocusable(false);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x44ffffff),
                rounded(fill, radius), rounded(Color.WHITE, radius)));
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void installDrag(View handle, boolean resize, boolean tap) {
        // A down/move gesture on blank space drags immediately after touch slop.
        final int slop = ViewConfiguration.get(controlContext).getScaledTouchSlop();
        handle.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY, startX, startY, startScale;
            private boolean moved;

            @Override public boolean onTouch(View view, MotionEvent event) {
                View control = remote.touchMode ? collapsed : panel;
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        ensureDpadFocus();
                        downX = event.getRawX();
                        downY = event.getRawY();
                        startX = control.getX();
                        startY = control.getY();
                        startScale = control.getScaleX();
                        moved = false;
                        view.setPressed(true);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downX;
                        float dy = event.getRawY() - downY;
                        if (!moved && Math.hypot(dx, dy) <= slop) return true;
                        moved = true;
                        if (resize) {
                            float width = panel.getWidth();
                            float height = panel.getHeight();
                            float delta = (width * dx + height * dy) / (width * width + height * height);
                            remote.scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, startScale + delta));
                            moveRemote(control, startX, startY, panelScale());
                        } else {
                            moveRemote(control, startX + dx, startY + dy, startScale);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        view.setPressed(false);
                        if (tap && !moved) view.performClick();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        view.setPressed(false);
                        return true;
                    default:
                        return true;
                }
            }
        });
        // View's OnTouchListener requires a clickable control to own the gesture.
        handle.setClickable(true);
    }

    private float panelScale() {
        if (panel.getWidth() == 0 || panel.getHeight() == 0) return remote.scale;
        return Math.min(remote.scale, Math.min(stage.getWidth() / (float) panel.getWidth(),
                stage.getHeight() / (float) panel.getHeight()));
    }

    private void positionRemote() {
        if (stage.getWidth() == 0 || stage.getHeight() == 0) return;
        // Both presentations use the same proportional position so switching modes
        // preserves the expanded panel's location and size.
        placeAtSavedPosition(panel, panelScale());
        placeAtSavedPosition(collapsed, 1f);
    }

    private void placeAtSavedPosition(View control, float scale) {
        control.setScaleX(scale);
        control.setScaleY(scale);
        float maxX = Math.max(0f, stage.getWidth() - control.getWidth() * scale);
        float maxY = Math.max(0f, stage.getHeight() - control.getHeight() * scale);
        control.setX(remote.xFraction == NO_POSITION ? Math.max(0f, maxX - dp(14))
                : maxX * remote.xFraction);
        control.setY(remote.yFraction == NO_POSITION ? Math.max(0f, maxY - dp(14))
                : maxY * remote.yFraction);
    }

    private void moveRemote(View control, float x, float y, float scale) {
        float maxX = stage.getWidth() - control.getWidth() * scale;
        float maxY = stage.getHeight() - control.getHeight() * scale;
        if (maxX > 0f) remote.xFraction = Math.max(0f, Math.min(1f, x / maxX));
        if (maxY > 0f) remote.yFraction = Math.max(0f, Math.min(1f, y / maxY));
        positionRemote();
    }

    private boolean belongsToContent(View view) {
        for (View current = view; current != null;
                current = current.getParent() instanceof View ? (View) current.getParent() : null) {
            if (current == content) return true;
        }
        return false;
    }

    private View restoreFocus() {
        View focused = content.findFocus();
        if (focused != null && !isInTouchMode()) return focused;

        // Tapping on the overlay can put the window back into touch mode and clear
        // TV-only focus. Restore it before dispatching the emulated hardware key.
        View previous = focused == null ? lastContentFocus : focused;
        if (belongsToContent(previous) && previous.isShown() && previous.isEnabled()
                && previous.requestFocusFromTouch()) {
            return previous;
        }

        ArrayList<View> candidates = new ArrayList<>();
        content.addFocusables(candidates, View.FOCUS_FORWARD, View.FOCUSABLES_ALL);
        for (View candidate : candidates) {
            if (candidate.isEnabled() && candidate.requestFocusFromTouch()) return candidate;
        }
        return null;
    }

    private void sendRemoteKey(int keyCode) {
        View focused = keyCode == KeyEvent.KEYCODE_BACK ? null : restoreFocus();
        long now = SystemClock.uptimeMillis();
        boolean downHandled = activity.dispatchKeyEvent(key(now, KeyEvent.ACTION_DOWN, keyCode));
        boolean upHandled = activity.dispatchKeyEvent(key(now, KeyEvent.ACTION_UP, keyCode));
        if (downHandled || upHandled || focused == null || content.findFocus() != focused) return;

        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            if (focused.isEnabled() && focused.isClickable()) focused.performClick();
            return;
        }
        int direction;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: direction = View.FOCUS_UP; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: direction = View.FOCUS_DOWN; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: direction = View.FOCUS_LEFT; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: direction = View.FOCUS_RIGHT; break;
            default: return;
        }
        if (content instanceof ViewGroup) {
            View next = FocusFinder.getInstance().findNextFocus((ViewGroup) content, focused, direction);
            if (next != null) next.requestFocus(direction);
        }
    }

    private KeyEvent key(long time, int action, int code) {
        return new KeyEvent(time, time, action, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD,
                0, 0, InputDevice.SOURCE_DPAD);
    }

    private static final class RemoteIcon extends View {
        private final String shape;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        RemoteIcon(Context context, String shape) {
            super(context);
            this.shape = shape;
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.8f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float size = Math.min(getWidth(), getHeight()) * 0.65f;
            canvas.save();
            canvas.translate((getWidth() - size) / 2f, (getHeight() - size) / 2f);
            canvas.scale(size / 24f, size / 24f);
            switch (shape) {
                case "up": line(canvas, 6, 15, 12, 9, 18, 15); break;
                case "down": line(canvas, 6, 9, 12, 15, 18, 9); break;
                case "left": line(canvas, 15, 6, 9, 12, 15, 18); break;
                case "right": line(canvas, 9, 6, 15, 12, 9, 18); break;
                case "resize":
                    canvas.drawLine(7, 7, 17, 17, paint);
                    line(canvas, 11, 17, 17, 17, 17, 11);
                    break;
                case "remote":
                    canvas.drawRoundRect(6, 2, 18, 22, 3, 3, paint);
                    canvas.drawCircle(12, 8, 2.5f, paint);
                    canvas.drawLine(9, 15, 15, 15, paint);
                    canvas.drawLine(9, 18, 15, 18, paint);
                    break;
                case "touch":
                    canvas.drawArc(7, 2, 16, 11, 165, 207, false, paint);
                    line(canvas, 10, 17, 10, 7, 12, 5, 14, 7, 14, 13,
                            16, 11, 20, 13, 20, 18, 18, 22, 12, 22, 6, 16, 7, 14, 10, 17);
                    break;
                default: break;
            }
            canvas.restore();
        }

        private void line(Canvas canvas, float... points) {
            path.reset();
            path.moveTo(points[0], points[1]);
            for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
            canvas.drawPath(path, paint);
        }
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
