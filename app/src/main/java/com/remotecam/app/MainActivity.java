package com.remotecam.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.os.Build;
import android.os.Bundle;
import android.view.*;
import android.widget.*;

import com.remoteyourcam.usb.ptp.Camera;

import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Dependency-light, adaptive Android shooting workspace. USB operations live in the controller. */
public final class MainActivity extends Activity implements CameraController.Observer {
    private static final int BG = 0xFF0D1117, PANEL = 0xFF171E28, TEXT = 0xFFE4EBF2;
    private static final int MUTED = 0xFF919DAF, ACCENT = 0xFF5DE0BC;
    private static final int EXPORT_PREVIEW = 42;
    private CameraController controller;
    private PreviewView preview;
    private TextView connectionState, deviceName, message, photoInfo, metadata;
    private Button connect, live, shutter, focus, review, gridButton, near, far;
    private final Map<Integer, Button> properties = new LinkedHashMap<>();
    private boolean reviewing;
    private boolean grid;
    private boolean resumed;
    private Bitmap exportBitmap;
    private final ExecutorService exports = Executors.newSingleThreadExecutor();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Object retained = getLastNonConfigurationInstance();
        controller = retained instanceof CameraController ? (CameraController) retained : new CameraController(this);
        grid = getPreferences(MODE_PRIVATE).getBoolean("grid", false);
        buildWorkspace();
        controller.observe(this);
        if (retained == null) handleUsbIntent(getIntent());
    }

    private void buildWorkspace() {
        LinearLayout root = column();
        root.setBackgroundColor(BG);
        root.setPadding(dp(20), dp(12), dp(20), dp(12));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = safe.left; top = safe.top; right = safe.right; bottom = safe.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft(); top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight(); bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(left + dp(20), top + dp(12), right + dp(20), bottom + dp(12));
            return insets;
        });
        LinearLayout header = row();
        TextView brand = text("REMOTE / CAM", 23, TEXT);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(brand, new LinearLayout.LayoutParams(0, dp(54), 1));
        Button help = button("连接指南", false, v -> showGuide());
        header.addView(help);
        root.addView(header);

        boolean wide = getResources().getConfiguration().screenWidthDp >= 720;
        LinearLayout workspace = wide ? row() : column();
        LinearLayout viewer = column();
        LinearLayout viewerHeader = row();
        TextView label = text("拍摄工作台", 16, TEXT);
        viewerHeader.addView(label, new LinearLayout.LayoutParams(0, dp(48), 1));
        gridButton = button(grid ? "网格 开" : "网格 关", false, v -> {
            grid = !grid;
            getPreferences(MODE_PRIVATE).edit().putBoolean("grid", grid).apply();
            preview.setGrid(grid);
            gridButton.setText(grid ? "网格 开" : "网格 关");
        });
        viewerHeader.addView(gridButton);
        viewer.addView(viewerHeader);
        preview = new PreviewView(this);
        preview.setGrid(grid);
        preview.setFocusListener(controller::focusAt);
        preview.setBackground(rounded(0xFF080C11, 18));
        preview.setClipToOutline(true);
        int previewHeight = dp(Math.max(210, (getResources().getConfiguration().screenWidthDp - 40) * 2 / 3));
        viewer.addView(preview, wide ? new LinearLayout.LayoutParams(-1, 0, 1)
                : new LinearLayout.LayoutParams(-1, previewHeight));
        metadata = text("USB PTP · 远程控制", 12, MUTED);
        viewer.addView(metadata, new LinearLayout.LayoutParams(-1, dp(40)));
        LinearLayout photoRow = row();
        photoInfo = text("拍摄后的 JPEG 可在这里查看", 12, MUTED);
        photoRow.addView(photoInfo, new LinearLayout.LayoutParams(0, -2, 1));
        review = button("查看照片", false, v -> {
            reviewing = !reviewing;
            preview.setReview(reviewing);
            onStateChanged();
        });
        photoRow.addView(review);
        photoRow.addView(button("导出预览", false, v -> exportPreview()));
        viewer.addView(photoRow);

        LinearLayout controls = column();
        controls.setPadding(dp(16), dp(16), dp(16), dp(16));
        controls.setBackground(rounded(PANEL, 18));
        connectionState = text("● 未连接", 12, ACCENT);
        deviceName = text("连接你的相机", 21, TEXT);
        deviceName.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        controls.addView(connectionState);
        controls.addView(deviceName);
        message = text("", 13, MUTED);
        controls.addView(message, spaced(-1, -2, 10));
        connect = button("连接相机", true, v -> chooseDevice());
        controls.addView(connect, spaced(-1, dp(52), 12));
        controls.addView(text("曝光控制", 15, TEXT), spaced(-1, -2, 24));
        addPropertyRow(controls, Camera.Property.ShutterSpeed, "快门", Camera.Property.ApertureValue, "光圈");
        addPropertyRow(controls, Camera.Property.IsoSpeed, "ISO", Camera.Property.Whitebalance, "白平衡");
        addPropertyRow(controls, Camera.Property.ExposureCompensation, "曝光补偿", Camera.Property.ColorTemperature, "色温");
        TextView modeHint = text("可调参数由机身模式和相机能力决定", 11, MUTED);
        controls.addView(modeHint, spaced(-1, -2, 8));
        LinearLayout liveRow = row();
        live = button("开启实时取景", false, v -> { reviewing = false; preview.setReview(false); controller.toggleLive(); });
        focus = button("自动对焦", false, v -> controller.focus());
        liveRow.addView(live, new LinearLayout.LayoutParams(0, dp(52), 1));
        liveRow.addView(focus, new LinearLayout.LayoutParams(0, dp(52), 1));
        controls.addView(liveRow, spaced(-1, -2, 20));
        LinearLayout lensRow = row();
        near = button("焦点 −", false, v -> driveLens(Camera.DriveLens.Near));
        far = button("焦点 +", false, v -> driveLens(Camera.DriveLens.Far));
        lensRow.addView(near, new LinearLayout.LayoutParams(0, dp(48), 1));
        lensRow.addView(far, new LinearLayout.LayoutParams(0, dp(48), 1));
        controls.addView(lensRow);
        shutter = button("◎  拍摄", true, v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            controller.capture();
        });
        controls.addView(shutter, spaced(-1, dp(72), 16));
        controls.addView(text("照片保留在相机存储卡 · App 仅显示预览", 11, MUTED), spaced(-1, -2, 12));

        if (wide) {
            LinearLayout.LayoutParams viewerParams = new LinearLayout.LayoutParams(0, -1, 1);
            viewerParams.setMarginEnd(dp(20));
            workspace.addView(viewer, viewerParams);
            ScrollView scroller = scroll(controls);
            workspace.addView(scroller, new LinearLayout.LayoutParams(dp(340), -1));
            root.addView(workspace, new LinearLayout.LayoutParams(-1, 0, 1));
        } else {
            workspace.addView(viewer);
            workspace.addView(controls, spaced(-1, -2, 18));
            root.addView(scroll(workspace), new LinearLayout.LayoutParams(-1, 0, 1));
        }
        setContentView(root);
        root.requestApplyInsets();
    }

    private void addPropertyRow(LinearLayout parent, int first, String firstLabel, int second, String secondLabel) {
        LinearLayout row = row();
        for (int i = 0; i < 2; i++) {
            int property = i == 0 ? first : second;
            String label = i == 0 ? firstLabel : secondLabel;
            Button button = button(label + "\n—", false, v -> selectProperty(property, label));
            button.setTag(label);
            properties.put(property, button);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(76), 1);
            if (i == 0) params.setMarginEnd(dp(8));
            row.addView(button, params);
        }
        parent.addView(row, spaced(-1, -2, 10));
    }

    @Override public void onStateChanged() {
        if (connectionState == null) return;
        Camera camera = controller.camera();
        boolean connected = controller.connected();
        String state = switch (controller.status) {
            case DISCONNECTED -> "未连接";
            case PERMISSION -> "等待 USB 授权";
            case CONNECTING -> "连接中";
            case CONNECTED -> "USB 已连接";
            case ERROR -> "连接异常";
        };
        connectionState.setText("● " + state);
        connectionState.setTextColor(controller.status == CameraController.Status.ERROR ? 0xFFFF9A8B : ACCENT);
        deviceName.setText(connected ? camera.getDeviceName() : "连接你的相机");
        message.setText(controller.message);
        connect.setText(connected ? "断开连接" : "连接相机");
        connect.setEnabled(controller.status != CameraController.Status.PERMISSION && controller.status != CameraController.Status.CONNECTING);
        live.setEnabled(connected && camera.isLiveViewSupported() && !controller.liveTransition && !controller.bulb);
        live.setText(controller.liveTransition ? "切换中…" : controller.live() ? "停止实时取景" : "开启实时取景");
        focus.setEnabled(connected && camera.isAutoFocusSupported() && !controller.focusPending && !controller.capturePending);
        focus.setText(controller.focusPending ? "对焦中…" : "自动对焦");
        near.setEnabled(connected && controller.live() && camera.isDriveLensSupported());
        far.setEnabled(near.isEnabled());
        shutter.setEnabled(connected && !controller.liveTransition && (!controller.capturePending || controller.bulb));
        shutter.setText(controller.bulb ? "■  结束曝光" : controller.capturePending ? "拍摄中…" : "◎  拍摄");
        review.setEnabled(controller.lastPhoto != null);
        review.setText(reviewing ? "返回取景" : "查看照片");
        preview.setReview(reviewing);
        preview.setBitmap(reviewing ? controller.lastPhoto : controller.live() ? controller.frame : null);
        preview.setPlaceholder(connected ? "相机已就绪" : "你的下一张作品，从这里开始",
                connected ? "开启实时取景，或直接拍摄" : "USB 连接 · Canon / Nikon");
        photoInfo.setText(controller.lastPhoto == null ? "拍摄后的 JPEG 可在这里查看"
                : "已接收 " + controller.shotCount + " 张 · " + controller.lastFilename);
        metadata.setText(connected ? "模式 " + propertyText(camera, Camera.Property.ShootingMode)
                + "  ·  " + (controller.live() ? "LIVE VIEW" : "USB PTP") : "USB PTP · 远程控制");
        for (Map.Entry<Integer, Button> entry : properties.entrySet()) {
            int property = entry.getKey();
            Button button = entry.getValue();
            button.setText(button.getTag() + "\n" + (connected ? propertyText(camera, property) : "—"));
            button.setEnabled(connected && !controller.bulb && camera.isSettingPropertyPossible(property)
                    && camera.getPropertyDesc(property).length > 0);
        }
        if (resumed && controller.live()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    @Override public void onFrame(Bitmap bitmap) { if (!reviewing) preview.setBitmap(bitmap); }

    private String propertyText(Camera camera, int property) {
        int value = camera.getProperty(property);
        if (value == Integer.MAX_VALUE) return "—";
        String formatted = camera.propertyToString(property, value);
        return formatted == null || formatted.isEmpty() || formatted.equals("?") ? "0x" + Integer.toHexString(value) : formatted;
    }

    private void chooseDevice() {
        if (controller.connected()) { controller.disconnect(); return; }
        List<UsbDevice> devices = controller.devices();
        if (devices.isEmpty()) { showGuide(); return; }
        String[] labels = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            UsbDevice d = devices.get(i);
            String name = d.getProductName();
            labels[i] = (name == null ? "Canon / Nikon 相机" : name) + "\nUSB " + d.getDeviceName();
        }
        new AlertDialog.Builder(this).setTitle("选择 USB 相机").setItems(labels,
                (dialog, which) -> controller.connect(devices.get(which))).setNegativeButton("取消", null).show();
    }
    private void selectProperty(int property, String title) {
        Camera camera = controller.camera();
        if (!controller.connected() || !camera.isSettingPropertyPossible(property)) return;
        int[] values = camera.getPropertyDesc(property).clone();
        String[] labels = new String[values.length];
        int checked = -1;
        for (int i = 0; i < values.length; i++) {
            labels[i] = camera.propertyToString(property, values[i]);
            if (labels[i] == null || labels[i].isEmpty() || labels[i].equals("?")) labels[i] = "0x" + Integer.toHexString(values[i]);
            if (values[i] == camera.getProperty(property)) checked = i;
        }
        new AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(labels, checked, (dialog, which) -> {
            if (controller.connected() && controller.camera() == camera && camera.isSettingPropertyPossible(property))
                camera.setProperty(property, values[which]);
            dialog.dismiss();
        }).setNegativeButton("取消", null).show();
    }
    private void driveLens(int direction) {
        Camera camera = controller.camera();
        if (controller.connected() && camera.isDriveLensSupported() && controller.live()) camera.driveLens(direction, Camera.DriveLens.Soft);
    }
    private void showGuide() {
        new AlertDialog.Builder(this).setTitle("让相机连接到手机")
                .setMessage("1. 使用支持数据传输的 USB 线和 OTG 转接头。\n\n2. 开启相机，将 USB 模式设置为 PTP / 电脑遥控，关闭相机 Wi-Fi。\n\n3. 点击「连接相机」，选择设备并允许 USB 访问。\n\n4. 建议先设为 M / Av / Tv 模式，存储卡选择 JPEG 或 RAW + JPEG。\n\n实时取景和镜头控制因机型而异。Canon 自动对焦暂未实现；Nikon 支持的机型可点击画面设置焦点。\n\n切到后台会停止取景和关闭会话。导出的是采样 JPEG 预览，原片保留在相机中。")
                .setPositiveButton("知道了", null).show();
    }

    private void exportPreview() {
        if (controller.lastPhoto == null) { Toast.makeText(this, "请先拍摄一张 JPEG 照片", Toast.LENGTH_SHORT).show(); return; }
        exportBitmap = controller.lastPhoto;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/jpeg").putExtra(Intent.EXTRA_TITLE, "RemoteCam-" + System.currentTimeMillis() + "-preview.jpg");
        startActivityForResult(intent, EXPORT_PREVIEW);
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != EXPORT_PREVIEW) return;
        Bitmap bitmap = exportBitmap;
        exportBitmap = null;
        if (resultCode != RESULT_OK || data == null || data.getData() == null || bitmap == null) return;
        android.net.Uri uri = data.getData();
        android.content.ContentResolver resolver = getApplicationContext().getContentResolver();
        exports.execute(() -> {
            String result;
            try (OutputStream stream = resolver.openOutputStream(uri, "w")) {
                if (stream == null || !bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)) throw new IllegalStateException("写入失败");
                result = "JPEG 预览已导出";
            } catch (Exception e) { result = "导出失败：" + e.getMessage(); }
            String notification = result;
            runOnUiThread(() -> Toast.makeText(getApplicationContext(), notification, Toast.LENGTH_LONG).show());
        });
    }

    private void handleUsbIntent(Intent intent) {
        UsbDevice device = CameraController.deviceExtra(intent);
        if (device != null && CameraController.findInterface(device) != null
                && controller.devices().stream().anyMatch(d -> d.getDeviceName().equals(device.getDeviceName()))) controller.connect(device);
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handleUsbIntent(intent); }
    @Override protected void onResume() { super.onResume(); resumed = true; controller.setForeground(true); controller.observe(this); }
    @Override protected void onPause() {
        resumed = false;
        if (!isChangingConfigurations()) controller.setForeground(false);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onPause();
    }
    @Override protected void onStop() {
        if (!isChangingConfigurations()) controller.disconnect();
        super.onStop();
    }
    @Override public Object onRetainNonConfigurationInstance() { controller.observe(null); return controller; }
    @Override protected void onDestroy() {
        if (!isChangingConfigurations()) controller.close();
        exports.shutdown();
        super.onDestroy();
    }

    private LinearLayout column() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout row() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.HORIZONTAL); layout.setGravity(Gravity.CENTER_VERTICAL); return layout; }
    private ScrollView scroll(View child) { ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false); scroll.addView(child); return scroll; }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setGravity(Gravity.CENTER_VERTICAL); return view;
    }
    private Button button(String value, boolean primary, View.OnClickListener listener) {
        Button button = new Button(this); button.setText(value); button.setTextSize(13); button.setAllCaps(false);
        button.setTextColor(primary ? BG : TEXT); button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48));
        android.content.res.ColorStateList colors = new android.content.res.ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{primary ? 0xFF28433F : 0xFF222A35, primary ? ACCENT : 0xFF222D3C});
        button.setBackgroundTintList(colors); button.setOnClickListener(listener);
        return button;
    }
    private GradientDrawable rounded(int color, int radius) { GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable; }
    private LinearLayout.LayoutParams spaced(int width, int height, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height); params.topMargin = dp(top); return params;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
