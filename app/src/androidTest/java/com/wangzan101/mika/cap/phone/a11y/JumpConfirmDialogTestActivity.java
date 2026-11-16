package com.wangzan101.mika.cap.phone.a11y;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Display;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 模拟各厂商系统级应用跳转确认对话框（Activity 型）：
 * 展示「"Mika Test" wants to open "<target>"」、未勾选的 "Always allow" 勾选框、
 * 以及 "Cancel" / "Open" 两个明确语义按钮。
 *
 * 当 {@link #EXTRA_IGNORE_CLICK} 为 true 时，"Open" 按钮故意不关闭弹窗，用于验证 1s 无效点击熔断。
 */
public class JumpConfirmDialogTestActivity extends Activity {
    public static final String EXTRA_TARGET = "target";
    public static final String EXTRA_IGNORE_CLICK = "ignoreClick";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String extraTarget = getIntent().getStringExtra(EXTRA_TARGET);
        final String target = (extraTarget != null && !extraTarget.isEmpty()) ? extraTarget : "com.android.settings";
        final boolean ignoreClick = getIntent().getBooleanExtra(EXTRA_IGNORE_CLICK, false);

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
        root.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView msg = new TextView(this);
        msg.setText("\"Mika Test\" wants to open \"" + targetLabel + "\"");
        msg.setTextSize(18f);
        root.addView(msg);

        CheckBox alwaysAllow = new CheckBox(this);
        alwaysAllow.setText("Always allow");
        alwaysAllow.setChecked(false);
        alwaysAllow.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(alwaysAllow);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);

        Button cancel = new Button(this);
        cancel.setText("Cancel");
        cancel.setOnClickListener(v -> finish());
        actions.addView(cancel);

        Button open = new Button(this);
        open.setText("Open");
        open.setOnClickListener(v -> {
            if (ignoreClick) {
                return;
            }
            Intent launch = getPackageManager().getLaunchIntentForPackage(target);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(
                    launch,
                    ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()
                );
            }
            finish();
        });
        actions.addView(open);

        root.addView(actions);
        setContentView(root);
    }
}
