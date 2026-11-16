package com.wangzan101.mika.cap.phone.a11y;

import android.app.ActivityOptions;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 模拟无独立 {@code taskId} 的悬浮窗型（{@code SYSTEM_ALERT_WINDOW} / {@code TYPE_APPLICATION_OVERLAY}）
 * 跳转确认框，用于在真机测试中验证仅靠窗口变更事件与全局 Window 扫描的识别、代点及等待能力。
 */
public class JumpConfirmOverlayService extends Service {
    public static final String EXTRA_TARGET = "target";
    public static final String EXTRA_ACTION = "action";
    public static final String ACTION_DISMISS = "dismiss";

    private WindowManager windowManager;
    private LinearLayout overlayView;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getStringExtra(EXTRA_ACTION) : null;
        if (ACTION_DISMISS.equals(action)) {
            removeOverlay();
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        String extraTarget = intent != null ? intent.getStringExtra(EXTRA_TARGET) : null;
        final String target = (extraTarget != null && !extraTarget.isEmpty()) ? extraTarget : "com.android.settings";
        showOverlay(target);
        return START_NOT_STICKY;
    }

    private void showOverlay(String target) {
        removeOverlay();
        windowManager = getSystemService(WindowManager.class);
        if (windowManager == null) return;

        PackageManager pm = getPackageManager();
        String targetLabel = target;
        try {
            CharSequence label = pm.getApplicationLabel(pm.getApplicationInfo(target, 0));
            if (label != null && label.length() > 0) {
                targetLabel = label.toString();
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        int pad = (int) (18 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView msg = new TextView(this);
        msg.setText("\"Mika Test\" wants to open \"" + targetLabel + "\"");
        msg.setTextColor(Color.BLACK);
        msg.setTextSize(17f);
        root.addView(msg);

        CheckBox alwaysAllow = new CheckBox(this);
        alwaysAllow.setText("Always allow");
        alwaysAllow.setTextColor(Color.DKGRAY);
        alwaysAllow.setChecked(false);
        root.addView(alwaysAllow);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);

        Button cancel = new Button(this);
        cancel.setText("Cancel");
        cancel.setOnClickListener(v -> {
            removeOverlay();
            stopSelf();
        });
        actions.addView(cancel);

        Button open = new Button(this);
        open.setText("Open");
        open.setOnClickListener(v -> {
            Intent launch = getPackageManager().getLaunchIntentForPackage(target);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(
                    launch,
                    ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()
                );
            }
            removeOverlay();
            stopSelf();
        });
        actions.addView(open);

        root.addView(actions);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.CENTER;
        params.setTitle("JumpConfirmOverlay");
        windowManager.addView(root, params);
        overlayView = root;
    }

    private void removeOverlay() {
        if (windowManager != null && overlayView != null) {
            try {
                windowManager.removeViewImmediate(overlayView);
            } catch (IllegalArgumentException ignored) {
            }
            overlayView = null;
        }
    }

    @Override
    public void onDestroy() {
        removeOverlay();
        super.onDestroy();
    }
}
