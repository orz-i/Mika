package com.wangzan101.mika.atlas;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 图谱端到端真机测试首页（运行在测试包独立进程，保持纯 Java 写法）。
 */
public class AtlasHomeTestActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 120, 48, 48);

        addLabel(root, "图谱首页", 26f);
        addLabel(root, "系统状态", 18f);
        addLabel(root, "功能列表", 18f);

        Button next = new Button(this);
        next.setText("进入详情页");
        next.setTextSize(18f);
        next.setOnClickListener(v -> startActivity(new Intent(this, AtlasDetailTestActivity.class)));
        root.addView(next);

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
