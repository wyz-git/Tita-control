package de.kai_morich.simple_usb_terminal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

public class JoystickView extends View {
    private Paint basePaint;
    private Paint hatPaint;
    private float centerX;
    private float centerY;
    private float baseRadius;
    private float hatRadius;
    
    // 新增：存储当前手柄位置
    private float currentX;
    private float currentY;
    
    private OnMoveListener onMoveListener;

    public interface OnMoveListener {
        void onMove(float angle, float strength);
    }

    public JoystickView(Context context) {
        super(context);
        init();
    }

    public JoystickView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public float getNormalizedX() {
        return (currentX - centerX) / baseRadius;
    }

    public float getNormalizedY() {
        return (currentY - centerY) / baseRadius;
    }

    private void init() {
        basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        basePaint.setColor(Color.parseColor("#4A5568"));
        basePaint.setStyle(Paint.Style.FILL_AND_STROKE);

        hatPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        hatPaint.setColor(Color.parseColor("#4299E1"));
        hatPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        centerX = w / 2f;
        centerY = h / 2f;
        currentX = centerX; // 初始化手柄位置
        currentY = centerY;
        baseRadius = Math.min(w, h) * 0.45f;
        hatRadius = baseRadius * 0.4f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        // 绘制底座（固定位置）
        canvas.drawCircle(centerX, centerY, baseRadius, basePaint);
        
        // 绘制手柄（动态位置）
        canvas.drawCircle(currentX, currentY, hatRadius, hatPaint);
        
        // 绘制方向指示线
        Paint linePaint = new Paint();
        linePaint.setColor(Color.WHITE);
        linePaint.setStrokeWidth(4f);
        canvas.drawLine(centerX, 0, centerX, getHeight(), linePaint);
        canvas.drawLine(0, centerY, getWidth(), centerY, linePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                // 计算偏移量
                float dx = x - centerX;
                float dy = y - centerY;
                float distance = (float) Math.sqrt(dx * dx + dy * dy);
                
                // 限制在底座范围内
                if (distance > baseRadius) {
                    dx *= baseRadius / distance;
                    dy *= baseRadius / distance;
                }
                
                // 更新手柄位置（关键修改）
                currentX = centerX + dx;
                currentY = centerY + dy;
                
                // 触发重绘
                invalidate();
                
                // 回调监听器
                if (onMoveListener != null) {
                    float angle = (float) Math.toDegrees(Math.atan2(dy, dx));
                    float strength = distance / baseRadius;
                    onMoveListener.onMove(angle, strength);
                }
                return true;
                
            case MotionEvent.ACTION_UP:
                // 复位手柄位置
                currentX = centerX;
                currentY = centerY;
                invalidate();
                
                if (onMoveListener != null) {
                    onMoveListener.onMove(0, 0);
                }
                return true;
        }
        return super.onTouchEvent(event);
    }

    public void setOnMoveListener(OnMoveListener listener) {
        this.onMoveListener = listener;
    }
}