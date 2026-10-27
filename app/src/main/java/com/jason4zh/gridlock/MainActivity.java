package com.jason4zh.gridlock;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

public class MainActivity extends Activity {

    private GridView grid;
    private FrameLayout root;
    private View panel;
    private Button floatingBtn;
    private Button captureBtn;
    private Button modeBtn;
    private Button rotBtn;
    private TextView status;

    private float density;
    private boolean statusPending = false;

    private final Runnable statusUpdater = new Runnable() {
        @Override
        public void run() {
            statusPending = false;
            refreshStatusText();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        density = getResources().getDisplayMetrics().density;

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0E1116);

        grid = new GridView(this);
        grid.setListener(new GridView.Listener() {
            @Override
            public void onStateChanged(GridView view) {
                updateStatus();
            }
        });
        root.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        int m = (int) (8 * density);

        panel = buildPanel();
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        panelLp.gravity = Gravity.BOTTOM;
        panelLp.setMargins(m, m, m, m);
        root.addView(panel, panelLp);

        floatingBtn = new Button(this);
        floatingBtn.setText("≡");
        floatingBtn.setVisibility(View.GONE);
        floatingBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                panel.setVisibility(View.VISIBLE);
                floatingBtn.setVisibility(View.GONE);
            }
        });
        FrameLayout.LayoutParams floatLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        floatLp.gravity = Gravity.BOTTOM | Gravity.END;
        floatLp.setMargins(m, m, m, m);
        root.addView(floatingBtn, floatLp);

        setContentView(root);
        grid.requestFocus();
        hideSystemBars();
        updateStatus();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
        }
    }

    private void hideSystemBars() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    // ------------------------------------------------------------- 界面构建

    private View buildPanel() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(0xE6131920);
        int pad = (int) (12 * density);
        box.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("GridLock · 桌面锁定网格");
        title.setTextColor(Color.WHITE);
        title.setTextSize(16f);
        box.addView(title);

        TextView hint = new TextView(this);
        hint.setText("① 点「捕获鼠标指针」（光标消失，才能拿到连续相对位移）\n"
                + "② 手机在桌面上平移/旋转，网格应在物理空间里保持不动\n"
                + "③ 方向反了勾 X/Y 反向；PCB 装转了勾「交换 X/Y」；跟手比例调 gain");
        hint.setTextColor(0xFFB0BEC5);
        hint.setTextSize(11f);
        hint.setPadding(0, (int) (4 * density), 0, (int) (6 * density));
        box.addView(hint);

        status = new TextView(this);
        status.setTextColor(0xFF9CCC65);
        status.setTextSize(11f);
        status.setPadding(0, 0, 0, (int) (6 * density));
        box.addView(status);

        // 第一行：捕获 / 输入模式
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);

        captureBtn = new Button(this);
        captureBtn.setText("捕获鼠标指针");
        captureBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (grid.isCaptured()) {
                    grid.stopCapture();
                } else {
                    grid.startCapture();
                }
                refreshCaptureButton();
                updateStatus();
            }
        });
        row1.addView(captureBtn, weightParams());

        modeBtn = new Button(this);
        modeBtn.setText("模式：自动");
        modeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                grid.cycleMode();
                modeBtn.setText("模式：" + grid.getModeName());
                updateStatus();
            }
        });
        row1.addView(modeBtn, weightParams());
        box.addView(row1);

        // 第二行：旋转来源 / 重置
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);

        rotBtn = new Button(this);
        rotBtn.setText("旋转：融合姿态");
        rotBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                grid.cycleRotationMode();
                rotBtn.setText("旋转：" + grid.getRotModeName());
                updateStatus();
            }
        });
        row2.addView(rotBtn, weightParams());

        Button resetBtn = new Button(this);
        resetBtn.setText("重置");
        resetBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                grid.reset();
                updateStatus();
            }
        });
        row2.addView(resetBtn, weightParams());
        box.addView(row2);

        // gain
        final TextView gainLabel = label("灵敏度 gain = 1.00（鼠标计数 → 屏幕像素）");
        box.addView(gainLabel);

        SeekBar gainBar = new SeekBar(this);
        gainBar.setMax(290);
        gainBar.setProgress(90);
        gainBar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgress(int progress) {
                float g = (progress + 10) / 100f;
                grid.setGain(g);
                gainLabel.setText(String.format(Locale.US,
                        "灵敏度 gain = %.2f（鼠标计数 → 屏幕像素）", g));
            }
        });
        box.addView(gainBar);

        // 网格间距
        final TextView spaceLabel = label("网格间距 = 48 dp");
        box.addView(spaceLabel);

        SeekBar spaceBar = new SeekBar(this);
        spaceBar.setMax(152);
        spaceBar.setProgress(40);
        spaceBar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgress(int progress) {
                float dp = 8f + progress;
                grid.setSpacingDp(dp);
                spaceLabel.setText(String.format(Locale.US, "网格间距 = %.0f dp", dp));
            }
        });
        box.addView(spaceBar);

        // 旋转支点（传感器偏心）补偿
        final TextView offXLabel = label("旋转支点补偿 X = 0 mm");
        box.addView(offXLabel);
        final TextView offYLabel = label("旋转支点补偿 Y = 0 mm");
        box.addView(offYLabel);

        SeekBar offXBar = new SeekBar(this);
        offXBar.setMax(160);
        offXBar.setProgress(80);
        offXBar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgress(int progress) {
                float mm = progress - 80;
                grid.setSensorOffsetsMm(mm, currentOffY);
                offXLabel.setText(String.format(Locale.US, "旋转支点补偿 X = %.0f mm", mm));
                currentOffX = mm;
            }
        });
        box.addView(offXBar);

        SeekBar offYBar = new SeekBar(this);
        offYBar.setMax(160);
        offYBar.setProgress(80);
        offYBar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgress(int progress) {
                float mm = progress - 80;
                grid.setSensorOffsetsMm(currentOffX, mm);
                offYLabel.setText(String.format(Locale.US, "旋转支点补偿 Y = %.0f mm", mm));
                currentOffY = mm;
            }
        });
        box.addView(offYBar);

        // 复选框行
        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);

        row3.addView(checkBox("X 反向", false, new CheckHandler() {
            @Override
            public void onChecked(boolean checked) {
                grid.setInvertX(checked);
            }
        }));
        row3.addView(checkBox("Y 反向", false, new CheckHandler() {
            @Override
            public void onChecked(boolean checked) {
                grid.setInvertY(checked);
            }
        }));
        row3.addView(checkBox("交换X/Y", false, new CheckHandler() {
            @Override
            public void onChecked(boolean checked) {
                grid.setSwapAxes(checked);
            }
        }));
        box.addView(row3);

        LinearLayout row4 = new LinearLayout(this);
        row4.setOrientation(LinearLayout.HORIZONTAL);
        row4.addView(checkBox("旋转反向", false, new CheckHandler() {
            @Override
            public void onChecked(boolean checked) {
                grid.setRotInvert(checked);
            }
        }));
        row4.addView(checkBox("遥测", true, new CheckHandler() {
            @Override
            public void onChecked(boolean checked) {
                grid.setShowTelemetry(checked);
            }
        }));

        Button hideBtn = new Button(this);
        hideBtn.setText("隐藏");
        hideBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                panel.setVisibility(View.GONE);
                floatingBtn.setVisibility(View.VISIBLE);
            }
        });
        row4.addView(hideBtn, weightParams());
        box.addView(row4);

        ScrollView scroller = new ScrollView(this);
        scroller.addView(box, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroller;
    }

    private float currentOffX = 0f;
    private float currentOffY = 0f;

    private TextView label(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFCFD8DC);
        tv.setTextSize(12f);
        return tv;
    }

    private LinearLayout.LayoutParams weightParams() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private interface CheckHandler {
        void onChecked(boolean checked);
    }

    private CheckBox checkBox(String text, boolean checked, final CheckHandler handler) {
        CheckBox box = new CheckBox(this);
        box.setText(text);
        box.setTextColor(0xFFCFD8DC);
        box.setTextSize(12f);
        box.setChecked(checked);
        box.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                handler.onChecked(isChecked);
            }
        });
        return box;
    }

    private static abstract class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        abstract void onProgress(int progress);

        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            onProgress(progress);
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }

    private void refreshCaptureButton() {
        captureBtn.setText(grid.isCaptured() ? "释放鼠标指针" : "捕获鼠标指针");
    }

    private void updateStatus() {
        if (status == null || statusPending) {
            return;
        }
        statusPending = true;
        status.postDelayed(statusUpdater, 120);
    }

    private void refreshStatusText() {
        status.setText(String.format(Locale.US,
                "pivot=(%.0f, %.0f) θ=%+.1f° events=%d gain=%.2f%s",
                grid.getPivotWorldX(), grid.getPivotWorldY(), grid.getThetaDeg(),
                grid.getEventCount(), grid.getGain(),
                grid.isCaptured() ? "  [已捕获]" : "  [未捕获]"));
    }

    // ---------------------------------------------------------- 输入分发兜底

    /**
     * 兜底通道：鼠标 hover / move 事件走这里。
     * 指针捕获时事件由 GridView.onCapturedPointerEvent 接收；
     * GridView 内部按 eventTime + 坐标 去重，两条通道同时喂也不会重复累加。
     */
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if (isMouseLike(ev)) {
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_HOVER_MOVE || action == MotionEvent.ACTION_MOVE) {
                grid.handleMouseEvent(ev);
                return true;
            }
        }
        return super.dispatchGenericMotionEvent(ev);
    }

    private boolean isMouseLike(MotionEvent ev) {
        int source = ev.getSource();
        return (source & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                || (source & InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD;
    }
}