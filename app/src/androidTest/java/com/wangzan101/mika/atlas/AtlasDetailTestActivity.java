package com.wangzan101.mika.atlas;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 图谱端到端真机测试详情页（同包二跳目标页，保持纯 Java 写法）。
 */
public class AtlasDetailTestActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 120, 48, 48);

        addLabel(root, "图谱详情", 26f);
        addLabel(root, "设备参数", 18f);
        addLabel(root, "运行信息", 18f);

        Button back = new Button(this);
        back.setText("返回首页");
        back.setTextSize(18f);
        back.setOnClickListener(v -> finish());
        root.addView(back);

        setContentView(root);
    }

    private void addLabel(LinearLayout root, String text, float sizeSp) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sizeSp);
        tv.setPadding(0, 16, 0, 16);
        root.addView(tv);
    }
}
