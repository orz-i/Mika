package com.wangzan101.mika.cap.phone.a11y;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/** A real virtual-display Activity with only pixels: no text or content description for the detector. */
public class LocalIconTestActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(new View(this) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final float iconX = 560f;
            private final float iconY = 140f;
            private final float radius = 27f;
            private boolean tapped;

            @Override protected void onDraw(Canvas canvas) {
                canvas.drawColor(Color.rgb(245, 245, 241));
                paint.setColor(Color.rgb(32, 34, 39));
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeWidth(.26f * radius);
                canvas.drawCircle(iconX - .21f * radius, iconY - .21f * radius,
                        .62f * radius, paint);
                canvas.drawLine(iconX + .23f * radius, iconY + .23f * radius,
                        iconX + .86f * radius, iconY + .86f * radius, paint);

                // A visual-only result marker outside the search region.
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(tapped ? Color.CYAN : Color.BLUE);
                canvas.drawRect(getWidth() - 48f, getHeight() - 48f,
                        getWidth() - 8f, getHeight() - 8f, paint);
            }

            @Override public boolean onTouchEvent(MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_UP &&
                        Math.abs(event.getX() - iconX) < 100f &&
                        Math.abs(event.getY() - iconY) < 100f) {
                    tapped = true;
                    invalidate();
                    performClick();
                }
                return true;
            }

            @Override public boolean performClick() {
                super.performClick();
                return true;
            }
        });
    }
}
