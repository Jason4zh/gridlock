package com.jason4zh.gridlock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/**
 * 桌面锁定网格视图。
 *
 * 把手机在桌面上的姿态记成二维刚体变换 (pivot, theta)：
 * - pivot：屏幕中心在桌面坐标系里的位置（px）
 * - theta：屏幕坐标系 → 桌面坐标系的旋转角（弧度）
 * 桌面内容点 p 映射到屏幕是 s = m + R(-theta)·(p - pivot)，m 为屏幕中心。
 * 绘制时对画布做逆变换，网格就“钉”在桌面上。
 *
 * 位移来源：背面鼠标 PCB 的相对位移（换算为 px）
 * 角度来源：陀螺仪 Z 轴积分，或融合姿态（旋转矢量）的绝对航向
 */
public class GridView extends View implements SensorEventListener {

    // ---- 输入取数方式 ----
    public static final int MODE_AUTO = 0;
    public static final int MODE_RELATIVE = 1;
    public static final int MODE_DIFF = 2;
    public static final int MODE_AXIS = 3;

    // ---- 旋转来源 ----
    public static final int ROT_FUSED = 0;
    public static final int ROT_GYRO = 1;
    public static final int ROT_OFF = 2;

    public interface Listener {
        void onStateChanged(GridView view);
    }

    private static final int COLOR_BG = 0xFF0E1116;

    // 桌面坐标系中的姿态
    private double pivotX = 0.0;
    private double pivotY = 0.0;
    private double theta = 0.0;

    private float gain = 1.0f;
    private float spacingDp = 48f;
    private float spacingPx;
    private float density = 1f;

    private boolean invertX = false;
    private boolean invertY = false;
    private boolean swapAxes = false;
    private boolean rotInvert = false;
    private boolean showTelemetry = true;
    private boolean captured = false;
    private int mode = MODE_AUTO;
    private int rotMode = ROT_FUSED;

    // 鼠标传感器相对屏幕中心的偏心量（px），补偿“纯旋转被误读成平移”
    private double sensorOffXPx = 0.0;
    private double sensorOffYPx = 0.0;

    // 遥测
    private float lastAbsX = Float.NaN;
    private float lastAbsY = Float.NaN;
    private long lastHandledTime = -1L;
    private int lastHandledAction = -1;
    private float lastHandledPx = Float.NaN;
    private float lastHandledPy = Float.NaN;
    private float lastDx = 0f;
    private float lastDy = 0f;
    private long eventCount = 0L;
    private String usedMode = "-";
    private String lastEventLine = "尚无鼠标事件";

    // 传感器
    private SensorManager sensorManager;
    private Sensor gyroSensor;
    private Sensor rvSensor;
    private final float[] rvMatrix = new float[9];
    private long lastGyroTs = 0L;
    private double fusedBase = Double.NaN;
    private String sensorInfo = "未注册";

    private final Paint minorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint majorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Listener listener;

    public GridView(Context context) {
        this(context, null);
    }

    public GridView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        density = getResources().getDisplayMetrics().density;
        spacingPx = spacingDp * density;

        minorPaint.setStyle(Paint.Style.STROKE);
        minorPaint.setStrokeWidth(Math.max(1f, 1.0f * density));
        minorPaint.setColor(0x33FFFFFF);

        majorPaint.setStyle(Paint.Style.STROKE);
        majorPaint.setStrokeWidth(Math.max(1f, 1.7f * density));
        majorPaint.setColor(0x70FFFFFF);

        axisPaint.setStyle(Paint.Style.STROKE);
        axisPaint.setStrokeWidth(Math.max(1f, 2.4f * density));
        axisPaint.setColor(0xFFFFB300);

        centerPaint.setStyle(Paint.Style.STROKE);
        centerPaint.setStrokeWidth(Math.max(1f, 2.0f * density));
        centerPaint.setColor(0xFF00E5FF);

        textPaint.setColor(0xCCFFFFFF);
        textPaint.setTextSize(11f * density);
        textPaint.setTypeface(Typeface.MONOSPACE);

        setBackgroundColor(COLOR_BG);
        setFocusable(true);
        setFocusableInTouchMode(true);

        sensorManager = (SensorManager) getContext().getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            rvSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        }
        sensorInfo = (gyroSensor != null ? "陀螺仪OK" : "陀螺仪无")
                + " " + (rvSensor != null ? "姿态OK" : "姿态无");
    }

    // ------------------------------------------------------ 传感器生命周期

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (sensorManager == null) {
            return;
        }
        if (gyroSensor != null) {
            sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_GAME);
        }
        if (rvSensor != null) {
            sensorManager.registerListener(this, rvSensor, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        int type = event.sensor.getType();
        if (type == Sensor.TYPE_GYROSCOPE) {
            handleGyro(event);
        } else if (type == Sensor.TYPE_ROTATION_VECTOR) {
            handleRotationVector(event);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // 不需要
    }

    /** 陀螺仪：对 Z 轴角速度积分得到偏航角 */
    private void handleGyro(SensorEvent event) {
        if (rotMode != ROT_GYRO || event.values.length < 3) {
            lastGyroTs = 0L;
            return;
        }
        if (lastGyroTs == 0L) {
            lastGyroTs = event.timestamp;
            return;
        }
        double dt = (event.timestamp - lastGyroTs) / 1e9;
        lastGyroTs = event.timestamp;
        if (dt <= 0 || dt > 0.25) {
            return;
        }
        // 设备 Z 轴朝上（屏幕朝天）时，正值 = 俯视逆时针，对应 theta 减小
        double dTheta = -event.values[2] * dt;
        if (rotInvert) {
            dTheta = -dTheta;
        }
        applyRotation(dTheta);
    }

    /** 融合姿态：用旋转矢量的绝对航向，无累积漂移 */
    private void handleRotationVector(SensorEvent event) {
        if (rotMode != ROT_FUSED) {
            return;
        }
        SensorManager.getRotationMatrixFromVector(rvMatrix, event.values);
        // 设备 x 轴投影到水平面后的方位角（世界系 x=东、y=北）
        double raw = Math.atan2(rvMatrix[3], rvMatrix[0]);
        double horizontal = Math.hypot(rvMatrix[0], rvMatrix[3]);
        if (horizontal < 0.35) {
            return; // 手机接近竖直，水平投影不可信
        }
        if (Double.isNaN(fusedBase)) {
            fusedBase = raw + theta; // 与当前角度接续，避免跳变
            return;
        }
        double target = -(raw - fusedBase);
        double dTheta = wrapToPi(target - theta);
        if (rotInvert) {
            dTheta = -dTheta;
        }
        applyRotation(dTheta);
    }

    /** 应用一次微小旋转（弧度），并补偿传感器偏心导致的伪平移 */
    private void applyRotation(double dTheta) {
        if (dTheta == 0.0 || Double.isNaN(dTheta)) {
            return;
        }
        theta = wrapToPi(theta + dTheta);
        if (sensorOffXPx != 0.0 || sensorOffYPx != 0.0) {
            // 传感器绕屏幕中心旋转 dθ 自身会位移 dθ×r，鼠标把它当平移报上来
            addBodyTranslation(dTheta * sensorOffYPx, -dTheta * sensorOffXPx);
        }
        postInvalidateOnAnimation();
        notifyState();
    }

    /** 把机体坐标（屏幕坐标系）下的位移换算到桌面坐标系后累加到 pivot */
    private void addBodyTranslation(double dxBody, double dyBody) {
        double c = Math.cos(theta);
        double s = Math.sin(theta);
        pivotX += c * dxBody - s * dyBody;
        pivotY += s * dxBody + c * dyBody;
    }

    private static double wrapToPi(double angle) {
        while (angle > Math.PI) {
            angle -= 2 * Math.PI;
        }
        while (angle < -Math.PI) {
            angle += 2 * Math.PI;
        }
        return angle;
    }

    // ---------------------------------------------------------------- 绘制

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        float sp = Math.max(6f, spacingPx);
        double ox = pivotX;
        double oy = pivotY;
        // 旋转后要覆盖整个屏幕，按对角线半径取绘制范围
        double radius = 0.5 * Math.hypot(w, h) + sp;
        double minX = ox - radius;
        double maxX = ox + radius;
        double minY = oy - radius;
        double maxY = oy + radius;

        int save = canvas.save();
        canvas.translate(w / 2f, h / 2f);
        canvas.rotate((float) Math.toDegrees(-theta));
        canvas.translate((float) -ox, (float) -oy);

        long n0 = (long) Math.floor(minX / sp);
        long n1 = (long) Math.ceil(maxX / sp);
        for (long n = n0; n <= n1; n++) {
            float x = (float) (n * sp);
            canvas.drawLine(x, (float) minY, x, (float) maxY,
                    (n % 5 == 0) ? majorPaint : minorPaint);
        }

        long m0 = (long) Math.floor(minY / sp);
        long m1 = (long) Math.ceil(maxY / sp);
        for (long m = m0; m <= m1; m++) {
            float y = (float) (m * sp);
            canvas.drawLine((float) minX, y, (float) maxX, y,
                    (m % 5 == 0) ? majorPaint : minorPaint);
        }

        // 桌面坐标系原点两条轴（x=0 / y=0），用来核对是否真的锁住了桌面
        canvas.drawLine(0f, (float) minY, 0f, (float) maxY, axisPaint);
        canvas.drawLine((float) minX, 0f, (float) maxX, 0f, axisPaint);

        canvas.restoreToCount(save);

        // 屏幕中心十字：随屏幕一起动，用来对比“屏幕参照物”
        float cx = w / 2f;
        float cy = h / 2f;
        float r = 16f * density;
        canvas.drawLine(cx - r, cy, cx + r, cy, centerPaint);
        canvas.drawLine(cx, cy - r, cx, cy + r, centerPaint);

        if (showTelemetry) {
            drawTelemetry(canvas);
        }
    }

    private void drawTelemetry(Canvas canvas) {
        float pad = 10f * density;
        float lineH = textPaint.getTextSize() * 1.4f;

        String[] lines = new String[] {
                String.format(Locale.US, "pivot = (%.1f, %.1f) px", pivotX, pivotY),
                String.format(Locale.US, "theta = %+.1f   %s%s", Math.toDegrees(theta),
                        getRotModeName(), rotInvert ? " (反向)" : ""),
                String.format(Locale.US, "gain = %.2f   间距 = %.0f dp", gain, spacingDp),
                String.format(Locale.US, "events = %d   last dx=%.1f dy=%.1f",
                        eventCount, lastDx, lastDy),
                "取数 = " + usedMode + (captured ? "   [指针已捕获]" : "   [未捕获]"),
                "传感器 = " + sensorInfo,
                lastEventLine
        };

        float y = pad + lineH;
        for (String s : lines) {
            canvas.drawText(s, pad, y, textPaint);
            y += lineH;
        }
    }

    // ---------------------------------------------------------------- 鼠标输入

    /** 处理一次鼠标移动事件（由 Activity 分发通道或指针捕获通道调用） */
    public void handleMouseEvent(MotionEvent e) {
        float relX = e.getAxisValue(MotionEvent.AXIS_RELATIVE_X);
        float relY = e.getAxisValue(MotionEvent.AXIS_RELATIVE_Y);
        float axisX = e.getAxisValue(MotionEvent.AXIS_X);
        float axisY = e.getAxisValue(MotionEvent.AXIS_Y);
        float px = e.getX();
        float py = e.getY();

        // 同一事件只处理一次（两条通道都可能在跑）
        int actionMasked = e.getActionMasked();
        long eventTime = e.getEventTime();
        if (eventTime == lastHandledTime && actionMasked == lastHandledAction
                && px == lastHandledPx && py == lastHandledPy) {
            return;
        }
        lastHandledTime = eventTime;
        lastHandledAction = actionMasked;
        lastHandledPx = px;
        lastHandledPy = py;

        float diffX = Float.NaN;
        float diffY = Float.NaN;
        if (!Float.isNaN(lastAbsX) && !Float.isNaN(lastAbsY)) {
            diffX = px - lastAbsX;
            diffY = py - lastAbsY;
        }
        lastAbsX = px;
        lastAbsY = py;

        boolean relValid = (relX != 0f || relY != 0f);
        boolean diffValid = !Float.isNaN(diffX);

        float dx;
        float dy;
        switch (mode) {
            case MODE_RELATIVE:
                dx = relX;
                dy = relY;
                usedMode = "RELATIVE（强制）";
                break;
            case MODE_DIFF:
                dx = diffValid ? diffX : 0f;
                dy = diffValid ? diffY : 0f;
                usedMode = "坐标差值（强制）";
                break;
            case MODE_AXIS:
                dx = axisX;
                dy = axisY;
                usedMode = "AXIS_X/Y（强制）";
                break;
            default:
                if (relValid) {
                    dx = relX;
                    dy = relY;
                    usedMode = "自动 → RELATIVE";
                } else if (diffValid) {
                    dx = diffX;
                    dy = diffY;
                    usedMode = "自动 → 坐标差值";
                } else {
                    dx = 0f;
                    dy = 0f;
                    usedMode = "自动（等待数据）";
                }
                break;
        }

        if (Float.isNaN(dx) || Float.isInfinite(dx)) {
            dx = 0f;
        }
        if (Float.isNaN(dy) || Float.isInfinite(dy)) {
            dy = 0f;
        }
        dx = clamp(dx, -2000f, 2000f);
        dy = clamp(dy, -2000f, 2000f);

        lastEventLine = String.format(Locale.US,
                "last: px=(%.1f,%.1f) axis=(%.1f,%.1f) rel=(%.1f,%.1f) act=%d",
                px, py, axisX, axisY, relX, relY, actionMasked);

        applyDelta(dx, dy);
    }

    /** 应用一次机体位移（鼠标计数换算成 px 之后） */
    public void applyDelta(float dx, float dy) {
        if (swapAxes) {
            float t = dx;
            dx = dy;
            dy = t;
        }
        float adx = invertX ? -dx : dx;
        float ady = invertY ? -dy : dy;

        addBodyTranslation(adx * gain, ady * gain);

        lastDx = adx;
        lastDy = ady;
        eventCount++;

        postInvalidateOnAnimation();
        notifyState();
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    // ------------------------------------------------------- 指针捕获通道

    public boolean startCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            lastAbsX = Float.NaN;
            lastAbsY = Float.NaN;
            requestPointerCapture();
            captured = true;
            postInvalidateOnAnimation();
            notifyState();
            return true;
        }
        return false;
    }

    public void stopCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            captured = false;
            releasePointerCapture();
            postInvalidateOnAnimation();
            notifyState();
        }
    }

    @Override
    public boolean onCapturedPointerEvent(MotionEvent event) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_HOVER_MOVE) {
                handleMouseEvent(event);
                return true;
            }
            return super.onCapturedPointerEvent(event);
        }
        return false;
    }

    // ---------------------------------------------------------------- 参数

    public void cycleMode() {
        mode = (mode + 1) % 4;
        lastAbsX = Float.NaN;
        lastAbsY = Float.NaN;
        postInvalidateOnAnimation();
    }

    public void cycleRotationMode() {
        rotMode = (rotMode + 1) % 3;
        lastGyroTs = 0L;
        fusedBase = Double.NaN; // 切换后以当前角度为新基准
        postInvalidateOnAnimation();
        notifyState();
    }

    public void reset() {
        pivotX = 0.0;
        pivotY = 0.0;
        theta = 0.0;
        fusedBase = Double.NaN;
        lastGyroTs = 0L;
        eventCount = 0;
        lastAbsX = Float.NaN;
        lastAbsY = Float.NaN;
        lastHandledTime = -1L;
        lastDx = 0f;
        lastDy = 0f;
        usedMode = "-";
        lastEventLine = "已重置";
        postInvalidateOnAnimation();
        notifyState();
    }

    public void setGain(float value) {
        gain = value;
        postInvalidateOnAnimation();
    }

    public void setSpacingDp(float value) {
        spacingDp = value;
        spacingPx = value * density;
        postInvalidateOnAnimation();
    }

    public void setInvertX(boolean value) {
        invertX = value;
    }

    public void setInvertY(boolean value) {
        invertY = value;
    }

    public void setSwapAxes(boolean value) {
        swapAxes = value;
    }

    public void setRotInvert(boolean value) {
        rotInvert = value;
    }

    /** 鼠标传感器相对屏幕中心的偏心量（毫米），用于旋转时消除伪平移 */
    public void setSensorOffsetsMm(float offXMm, float offYMm) {
        float xdpi = getResources().getDisplayMetrics().xdpi;
        if (xdpi <= 0f) {
            xdpi = 400f;
        }
        sensorOffXPx = offXMm * xdpi / 25.4;
        sensorOffYPx = offYMm * xdpi / 25.4;
        postInvalidateOnAnimation();
    }

    public void setShowTelemetry(boolean value) {
        showTelemetry = value;
        postInvalidateOnAnimation();
    }

    public void setListener(Listener value) {
        listener = value;
    }

    private void notifyState() {
        if (listener != null) {
            listener.onStateChanged(this);
        }
    }

    public double getPivotWorldX() {
        return pivotX;
    }

    public double getPivotWorldY() {
        return pivotY;
    }

    public double getThetaDeg() {
        return Math.toDegrees(theta);
    }

    public float getGain() {
        return gain;
    }

    public long getEventCount() {
        return eventCount;
    }

    public boolean isCaptured() {
        return captured;
    }

    public String getSensorInfo() {
        return sensorInfo;
    }

    public String getModeName() {
        switch (mode) {
            case MODE_RELATIVE:
                return "相对轴";
            case MODE_DIFF:
                return "坐标差";
            case MODE_AXIS:
                return "AXIS";
            default:
                return "自动";
        }
    }

    public String getRotModeName() {
        switch (rotMode) {
            case ROT_GYRO:
                return "陀螺仪";
            case ROT_OFF:
                return "关";
            default:
                return "融合姿态";
        }
    }
}