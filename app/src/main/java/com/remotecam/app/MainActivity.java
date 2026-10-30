package com.remotecam.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.util.Size;
import android.view.*;
import android.widget.*;

import com.remoteyourcam.usb.ptp.Camera;

import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/** Adaptive Android shooting workspace with USB-only remote library/download workflow. */
public final class MainActivity extends Activity implements CameraController.Observer {
    private static final int BG = 0xFF0D1117, PANEL = 0xFF171E28, TEXT = 0xFFE4EBF2;
    private static final int MUTED = 0xFF919DAF, ACCENT = 0xFF5DE0BC;
    private static final int EXPORT_PREVIEW = 42;
    private static final int PICK_DOWNLOAD_FOLDER = 43;

    private CameraController controller;
    private PreviewView preview;
    private TextView connectionState, deviceName, message, photoInfo, metadata;
    private Button connect, live, shutter, focus, review, gridButton, near, far, libraryButton;
    private final Map<Integer, Button> properties = new LinkedHashMap<>();
    private boolean reviewing;
    private boolean grid;
    private boolean resumed;
    private Bitmap exportBitmap;
    private final ExecutorService exports = Executors.newSingleThreadExecutor();

    private FrameLayout screenRoot;
    private LinearLayout libraryOverlay;
    private TextView libraryStatus, librarySelection, libraryFolder;
    private Button libraryRefresh, libraryClose, librarySelectAll, libraryClear, libraryDownloadSelected,
            libraryChooseFolder;
    private GridView libraryGrid;
    private MediaLibraryAdapter libraryAdapter;
    private final List<CameraMediaEntry> libraryEntries = new ArrayList<>();
    private final List<CameraAlbumItem> libraryItems = new ArrayList<>();
    private final Set<String> selectedAlbumIds = new LinkedHashSet<>();
    private final Set<Integer> pendingThumbnailHandles = new HashSet<>();
    private final Set<Integer> failedThumbnailHandles = new HashSet<>();
    private int libraryMergeToken;
    private Uri downloadFolderUri;
    private ArrayList<String> pendingDownloadAlbumIds;
    private String pendingDownloadTitle;

    private FrameLayout viewerOverlay;
    private PreviewView viewerPreview;
    private TextView viewerTitle, viewerInfo;
    private Button viewerClose, viewerJpeg, viewerRaw, viewerDownload, viewerPrev, viewerNext;
    private CameraAlbumItem viewerItem;
    private int viewerLargeHandle = -1;

    private final List<DownloadTask> downloadTasks = new ArrayList<>();
    private DownloadTask activeDownloadTask;
    private int downloadTaskSeq;
    private LinearLayout downloadOverlay, downloadList, downloadStrip;
    private TextView downloadStripText;
    private Button downloadQueueButton;
    private ProgressBar downloadStripProgress;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Object retained = getLastNonConfigurationInstance();
        controller = retained instanceof CameraController ? (CameraController) retained : new CameraController(this);
        grid = getPreferences(MODE_PRIVATE).getBoolean("grid", false);
        String storedFolder = getPreferences(MODE_PRIVATE).getString("download_tree_uri", null);
        if (storedFolder != null) downloadFolderUri = Uri.parse(storedFolder);
        buildWorkspace();
        controller.observe(this);
        if (retained == null) handleUsbIntent(getIntent());
    }

    private void buildWorkspace() {
        LinearLayout workspaceRoot = column();
        workspaceRoot.setBackgroundColor(BG);
        workspaceRoot.setPadding(dp(20), dp(12), dp(20), dp(12));
        workspaceRoot.setOnApplyWindowInsetsListener((view, insets) -> {
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
        header.addView(button("连接指南", false, v -> showGuide()));
        workspaceRoot.addView(header);

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
        viewer.addView(preview, wide ? new LinearLayout.LayoutParams(-1, 0, 1) : new LinearLayout.LayoutParams(-1, previewHeight));
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
        libraryButton = button("照片库", false, v -> openLibrary());
        photoRow.addView(libraryButton);
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
        controls.addView(text("可调参数由机身模式和相机能力决定", 11, MUTED), spaced(-1, -2, 8));

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
        controls.addView(text("照片库以网格实时加载，点击查看大图、长按下载原图，JPEG+RAW 自动配对", 11, MUTED), spaced(-1, -2, 12));

        if (wide) {
            LinearLayout.LayoutParams viewerParams = new LinearLayout.LayoutParams(0, -1, 1);
            viewerParams.setMarginEnd(dp(20));
            workspace.addView(viewer, viewerParams);
            workspace.addView(scroll(controls), new LinearLayout.LayoutParams(dp(340), -1));
            workspaceRoot.addView(workspace, new LinearLayout.LayoutParams(-1, 0, 1));
        } else {
            workspace.addView(viewer);
            workspace.addView(controls, spaced(-1, -2, 18));
            workspaceRoot.addView(scroll(workspace), new LinearLayout.LayoutParams(-1, 0, 1));
        }

        screenRoot = new FrameLayout(this);
        screenRoot.addView(workspaceRoot, new FrameLayout.LayoutParams(-1, -1));
        libraryOverlay = buildLibraryOverlay();
        libraryOverlay.setVisibility(View.GONE);
        screenRoot.addView(libraryOverlay, new FrameLayout.LayoutParams(-1, -1));
        viewerOverlay = buildViewerOverlay();
        screenRoot.addView(viewerOverlay, new FrameLayout.LayoutParams(-1, -1));
        downloadOverlay = buildDownloadOverlay();
        downloadOverlay.setVisibility(View.GONE);
        screenRoot.addView(downloadOverlay, new FrameLayout.LayoutParams(-1, -1));
        setContentView(screenRoot);
        workspaceRoot.requestApplyInsets();
    }

    private LinearLayout buildLibraryOverlay() {
        LinearLayout overlay = column();
        overlay.setBackgroundColor(0xF20B1118);
        overlay.setPadding(dp(16), dp(16), dp(16), dp(16));

        LinearLayout header = row();
        TextView title = text("相机照片库", 20, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        libraryRefresh = button("刷新", false, v -> refreshLibrary());
        libraryClose = button("关闭", false, v -> closeLibrary());
        header.addView(libraryRefresh);
        header.addView(libraryClose);
        overlay.addView(header);

        libraryStatus = text("从相机读取照片列表…", 12, MUTED);
        overlay.addView(libraryStatus, spaced(-1, -2, 10));
        librarySelection = text("点击查看大图 · 长按下载原图", 12, MUTED);
        overlay.addView(librarySelection, spaced(-1, -2, 4));

        LinearLayout selectRow = row();
        librarySelectAll = button("全选", false, v -> {
            selectedAlbumIds.clear();
            for (CameraAlbumItem item : libraryItems) selectedAlbumIds.add(item.id);
            updateLibrarySelectionUi();
        });
        libraryClear = button("清空选择", false, v -> {
            selectedAlbumIds.clear();
            updateLibrarySelectionUi();
        });
        selectRow.addView(librarySelectAll, new LinearLayout.LayoutParams(0, dp(44), 1));
        selectRow.addView(libraryClear, new LinearLayout.LayoutParams(0, dp(44), 1));
        overlay.addView(selectRow, spaced(-1, -2, 6));

        libraryGrid = new GridView(this);
        int columns = Math.max(3, getResources().getConfiguration().screenWidthDp / 130);
        libraryGrid.setNumColumns(columns);
        libraryGrid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        libraryGrid.setHorizontalSpacing(dp(6));
        libraryGrid.setVerticalSpacing(dp(6));
        libraryGrid.setClipToPadding(false);
        libraryGrid.setSelector(new ColorDrawable(0x2225A18E));
        libraryAdapter = new MediaLibraryAdapter(this);
        libraryAdapter.setThumbnailRequester(this::requestThumbnailForItem);
        libraryGrid.setAdapter(libraryAdapter);
        libraryGrid.setOnItemClickListener((parent, view, position, id) -> showViewer(libraryAdapter.itemAt(position)));
        libraryGrid.setOnItemLongClickListener((parent, view, position, id) -> {
            showItemActions(libraryAdapter.itemAt(position));
            return true;
        });
        libraryGrid.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int scrollState) { }
            @Override public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                prefetchVisibleThumbnails(firstVisibleItem, visibleItemCount);
            }
        });
        LinearLayout.LayoutParams gridParams = new LinearLayout.LayoutParams(-1, 0, 1);
        gridParams.topMargin = dp(10);
        overlay.addView(libraryGrid, gridParams);

        libraryFolder = text(downloadFolderUri == null ? "下载目录：未选择" : "下载目录：" + folderLabel(downloadFolderUri), 11, MUTED);
        overlay.addView(libraryFolder, spaced(-1, -2, 10));

        downloadStrip = column();
        downloadStrip.setBackground(rounded(PANEL, 14));
        downloadStrip.setPadding(dp(10), dp(8), dp(10), dp(10));
        downloadStrip.setVisibility(View.GONE);
        downloadStrip.setOnClickListener(v -> openDownloads());
        downloadStripText = text("", 12, TEXT);
        downloadStrip.addView(downloadStripText);
        downloadStripProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        downloadStripProgress.setMax(100);
        LinearLayout.LayoutParams stripProgressParams = new LinearLayout.LayoutParams(-1, dp(6));
        stripProgressParams.topMargin = dp(6);
        downloadStrip.addView(downloadStripProgress, stripProgressParams);
        overlay.addView(downloadStrip, spaced(-1, -2, 8));

        LinearLayout actions = row();
        libraryChooseFolder = button("选择下载目录", false, v -> pickDownloadFolder(null));
        downloadQueueButton = button("下载队列", false, v -> openDownloads());
        actions.addView(libraryChooseFolder, new LinearLayout.LayoutParams(0, dp(48), 1));
        actions.addView(downloadQueueButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        overlay.addView(actions, spaced(-1, -2, 6));

        libraryDownloadSelected = button("后台批量下载所选", true, v -> requestDownload(selectedEntries(), selectedTitle()));
        overlay.addView(libraryDownloadSelected, spaced(-1, dp(48), 8));
        return overlay;
    }

    /** Full-screen download queue with per-task progress. */
    private LinearLayout buildDownloadOverlay() {
        LinearLayout overlay = column();
        overlay.setBackgroundColor(0xF20B1118);
        overlay.setPadding(dp(16), dp(16), dp(16), dp(16));

        LinearLayout header = row();
        TextView title = text("下载任务", 20, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button close = button("关闭", false, v -> downloadOverlay.setVisibility(View.GONE));
        header.addView(close);
        overlay.addView(header);

        downloadList = column();
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(-1, 0, 1);
        listParams.topMargin = dp(12);
        overlay.addView(scroll(downloadList), listParams);

        LinearLayout footer = row();
        Button cancelCurrent = button("取消当前下载", false, v -> cancelCurrentDownload());
        Button clearFinished = button("清空已完成", false, v -> {
            downloadTasks.removeIf(DownloadTask::finished);
            refreshDownloadUi();
        });
        footer.addView(cancelCurrent, new LinearLayout.LayoutParams(0, dp(48), 1));
        footer.addView(clearFinished, new LinearLayout.LayoutParams(0, dp(48), 1));
        overlay.addView(footer, spaced(-1, -2, 8));
        return overlay;
    }

    private void openDownloads() {
        downloadOverlay.setVisibility(View.VISIBLE);
        refreshDownloadUi();
    }

    private void cancelCurrentDownload() {
        if (activeDownloadTask != null) {
            activeDownloadTask.state = DownloadTask.State.CANCELLED;
            for (DownloadTask task : downloadTasks) {
                if (task.state == DownloadTask.State.QUEUED) task.state = DownloadTask.State.CANCELLED;
            }
        }
        controller.cancelDownloads();
        activeDownloadTask = null;
        refreshDownloadUi();
        Toast.makeText(this, "已取消当前下载", Toast.LENGTH_SHORT).show();
    }

    /** Rebuilds the queue panel and the compact progress strip. */
    private void refreshDownloadUi() {
        if (downloadList == null) return;
        int queued = 0;
        int running = 0;
        int finished = 0;
        for (DownloadTask task : downloadTasks) {
            if (task.state == DownloadTask.State.QUEUED) queued++;
            else if (task.state == DownloadTask.State.RUNNING) running++;
            else if (task.finished()) finished++;
        }
        if (downloadOverlay.getVisibility() == View.VISIBLE) renderDownloadList();
        updateDownloadStrip(queued);
        if (downloadQueueButton != null) {
            boolean active = queued + running > 0;
            downloadQueueButton.setText(active ? "下载队列 (" + (queued + running) + ")" : "下载队列");
        }
        if (finished > 0 && queued + running == 0 && libraryStatus != null && activeDownloadTask == null) {
            libraryStatus.setText("下载队列已完成，共 " + finished + " 个任务");
        }
    }

    private void renderDownloadList() {
        downloadList.removeAllViews();
        for (DownloadTask task : downloadTasks) {
            LinearLayout card = column();
            card.setBackground(rounded(PANEL, 14));
            card.setPadding(dp(12), dp(10), dp(12), dp(12));
            TextView name = text(task.title, 14, TEXT);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            card.addView(name);
            TextView status = text(task.statusText(), 11, task.state == DownloadTask.State.FAILED ? 0xFFE7A65A : MUTED);
            card.addView(status, spaced(-1, -2, 4));
            ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            bar.setProgress(task.percent());
            LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(-1, dp(6));
            barParams.topMargin = dp(6);
            card.addView(bar, barParams);
            if (!task.errors.isEmpty()) {
                TextView errors = text(String.join("\n", task.errors), 10, 0xFFE7A65A);
                card.addView(errors, spaced(-1, -2, 6));
            }
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
            cardParams.bottomMargin = dp(10);
            downloadList.addView(card, cardParams);
        }
        if (downloadTasks.isEmpty()) {
            downloadList.addView(text("暂无下载任务。长按照片可下载原图，或从照片库批量下载。", 12, MUTED), spaced(-1, -2, 8));
        }
    }

    /** Compact always-visible progress bar on top of the gallery. */
    private void updateDownloadStrip(int queued) {
        downloadStrip.setVisibility(downloadTasks.isEmpty() ? View.GONE : View.VISIBLE);
        if (downloadTasks.isEmpty()) return;
        DownloadTask current = activeDownloadTask != null ? activeDownloadTask : downloadTasks.get(downloadTasks.size() - 1);
        downloadStripText.setText(current.title + " · " + current.statusText()
                + (queued > 0 ? " · 还有 " + queued + " 个任务排队" : ""));
        downloadStripProgress.setProgress(current.percent());
    }

    /** Full-screen viewer: pinch zoom, auto loads the JPEG original, switches to RAW embedded preview. */
    private FrameLayout buildViewerOverlay() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xFA05080C);

        viewerPreview = new PreviewView(this);
        viewerPreview.setReview(true);
        viewerPreview.setPlaceholder("正在读取照片", "上下滑动切换 · 双指缩放 · 双击复位");
        viewerPreview.setSwipeListener(next -> navigateViewer(next ? 1 : -1));
        overlay.addView(viewerPreview, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = row();
        top.setPadding(dp(16), dp(12), dp(8), dp(12));
        top.setBackgroundColor(0x88000000);
        viewerTitle = text("", 16, TEXT);
        viewerTitle.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(viewerTitle, new LinearLayout.LayoutParams(0, -2, 1));
        viewerClose = button("关闭", false, v -> closeViewer());
        top.addView(viewerClose);
        overlay.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        LinearLayout bottom = column();
        bottom.setPadding(dp(16), dp(12), dp(16), dp(16));
        bottom.setBackgroundColor(0xCC10161E);
        viewerInfo = text("", 12, MUTED);
        bottom.addView(viewerInfo);

        LinearLayout navigation = row();
        viewerPrev = button("← 上一张", false, v -> navigateViewer(-1));
        viewerNext = button("下一张 →", false, v -> navigateViewer(1));
        navigation.addView(viewerPrev, new LinearLayout.LayoutParams(0, dp(44), 1));
        navigation.addView(viewerNext, new LinearLayout.LayoutParams(0, dp(44), 1));
        bottom.addView(navigation, spaced(-1, -2, 8));

        LinearLayout actions = row();
        viewerJpeg = button("JPEG 原图", false, v -> {
            if (viewerItem != null && viewerItem.jpegAsset() != null) viewerLoadLarge(viewerItem.jpegAsset());
        });
        viewerRaw = button("RAW 预览", false, v -> {
            if (viewerItem != null && viewerItem.rawAsset() != null) viewerLoadLarge(viewerItem.rawAsset());
        });
        viewerDownload = button("下载原图", true, v -> {
            if (viewerItem != null) requestDownload(viewerItem.assets, viewerItem.title());
        });
        actions.addView(viewerJpeg, new LinearLayout.LayoutParams(0, dp(48), 1));
        actions.addView(viewerRaw, new LinearLayout.LayoutParams(0, dp(48), 1));
        actions.addView(viewerDownload, new LinearLayout.LayoutParams(0, dp(48), 1));
        bottom.addView(actions, spaced(-1, -2, 10));
        overlay.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        overlay.setVisibility(View.GONE);
        return overlay;
    }

    private void showViewer(CameraAlbumItem item) {
        if (item == null) return;
        viewerItem = item;
        viewerLargeHandle = -1;
        viewerOverlay.setVisibility(View.VISIBLE);
        viewerPreview.setBitmap(item.coverBitmap());
        updateViewerChrome();
        if (item.cover() != null && item.coverBitmap() == null) requestThumbnailForItem(item);
        CameraMediaEntry jpeg = item.jpegAsset();
        if (jpeg != null) viewerLoadLarge(jpeg);
    }

    /** Scrolls the viewer to the previous / next photo in the grid. */
    private void navigateViewer(int delta) {
        if (viewerItem == null || libraryItems.isEmpty()) return;
        int current = -1;
        for (int i = 0; i < libraryItems.size(); i++) {
            if (libraryItems.get(i).id.equals(viewerItem.id)) { current = i; break; }
        }
        int target = (current < 0 ? 0 : current) + delta;
        if (target < 0 || target >= libraryItems.size()) {
            Toast.makeText(this, current < 0 ? "当前不在照片列表中" : (delta > 0 ? "已经是最后一张" : "已经是第一张"), Toast.LENGTH_SHORT).show();
            return;
        }
        CameraAlbumItem next = libraryItems.get(target);
        showViewer(next);
        viewerPreview.setAlpha(0.35f);
        viewerPreview.animate().alpha(1f).setDuration(160).start();
    }

    private void updateViewerChrome() {
        CameraAlbumItem item = viewerItem;
        if (item == null) return;
        int index = -1;
        for (int i = 0; i < libraryItems.size(); i++) {
            if (libraryItems.get(i).id.equals(item.id)) { index = i; break; }
        }
        viewerTitle.setText(index >= 0 ? item.title() + "  (" + (index + 1) + "/" + libraryItems.size() + ")" : item.title());
        viewerInfo.setText(item.subtitle() + " · " + item.badge() + " · 上下滑动切换照片");
        updateViewerButtons();
    }

    private void closeViewer() {
        viewerOverlay.setVisibility(View.GONE);
        viewerItem = null;
    }

    private void updateViewerButtons() {
        if (viewerJpeg == null) return;
        boolean hasItem = viewerItem != null;
        viewerJpeg.setVisibility(hasItem && viewerItem.hasJpeg() ? View.VISIBLE : View.GONE);
        viewerRaw.setVisibility(hasItem && viewerItem.hasRaw() ? View.VISIBLE : View.GONE);
        viewerDownload.setEnabled(hasItem && controller.connected());
        if (viewerPrev != null) viewerPrev.setEnabled(hasItem && libraryItems.size() > 1);
        if (viewerNext != null) viewerNext.setEnabled(hasItem && libraryItems.size() > 1);
    }

    private void viewerLoadLarge(CameraMediaEntry entry) {
        Size target = new Size(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels);
        controller.loadLargePreview(entry, target, new CameraController.MediaPreviewCallback() {
            @Override public void onStarted(CameraMediaEntry current) {
                viewerInfo.setText("正在读取 " + current.displayName() + " …");
            }

            @Override public void onLoaded(CameraMediaEntry current, Bitmap bitmap, String note) {
                if (viewerItem == null) return;
                viewerLargeHandle = current.handle;
                viewerPreview.setBitmap(bitmap);
                viewerInfo.setText(note + " · " + current.displayName());
            }

            @Override public void onError(CameraMediaEntry current, String error) {
                viewerInfo.setText(error);
                Toast.makeText(getApplicationContext(), error, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showItemActions(CameraAlbumItem item) {
        if (item == null) return;
        boolean selected = selectedAlbumIds.contains(item.id);
        String[] options = {
                "下载原图（" + item.assets.size() + " 个文件 · " + CameraMediaEntry.humanBytes(item.totalBytes()) + "）",
                selected ? "取消选择该组" : "选择该组（用于批量下载）",
                "查看大图"
        };
        new AlertDialog.Builder(this).setTitle(item.title())
                .setItems(options, (dialog, which) -> {
                    if (which == 0) requestDownload(item.assets, item.title());
                    else if (which == 1) {
                        if (selected) selectedAlbumIds.remove(item.id);
                        else selectedAlbumIds.add(item.id);
                        updateLibrarySelectionUi();
                    } else showViewer(item);
                })
                .setNegativeButton("取消", null)
                .show();
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
        libraryButton.setEnabled(connected);
        preview.setReview(reviewing);
        preview.setBitmap(reviewing ? controller.lastPhoto : controller.live() ? controller.frame : null);
        preview.setPlaceholder(connected ? "相机已就绪" : "你的下一张作品，从这里开始",
                connected ? "开启实时取景，或打开照片库浏览相机存储卡" : "USB 连接 · Canon / Nikon");
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
        updateLibraryButtons();
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
                .setMessage("1. 使用支持数据传输的 USB 线和 OTG 转接头。\n\n2. 开启相机，将 USB 模式设置为 PTP / 电脑遥控，关闭相机 Wi‑Fi。\n\n3. 点击「连接相机」，选择设备并允许 USB 访问。\n\n4. 打开「照片库」，照片以网格方式边加载边显示，JPEG+RAW 自动配对为一组。\n\n5. 点击任意照片查看大图与原图（支持双指缩放），可切换 RAW 内嵌预览；长按照片可下载原图或加入批量下载。\n\n批量下载支持在后台继续进行；缩略图按需懒加载，适合大量照片卡。")
                .setPositiveButton("知道了", null).show();
    }

    private void openLibrary() {
        if (!controller.connected()) {
            Toast.makeText(this, "请先连接相机", Toast.LENGTH_SHORT).show();
            return;
        }
        libraryOverlay.setVisibility(View.VISIBLE);
        if (libraryEntries.isEmpty()) refreshLibrary();
        else {
            updateLibraryButtons();
            prefetchVisibleThumbnails(libraryGrid.getFirstVisiblePosition(), libraryGrid.getChildCount());
        }
    }

    private void closeLibrary() {
        libraryOverlay.setVisibility(View.GONE);
    }

    private void refreshLibrary() {
        if (!controller.connected()) {
            libraryStatus.setText("请先连接相机");
            return;
        }
        libraryStatus.setText("正在从相机读取照片元数据…");
        libraryEntries.clear();
        libraryItems.clear();
        selectedAlbumIds.clear();
        pendingThumbnailHandles.clear();
        failedThumbnailHandles.clear();
        libraryAdapter.replace(Collections.emptyList());
        updateLibrarySelectionUi();

        controller.loadMediaLibrary(new CameraController.MediaLibraryCallback() {
            @Override public void onStarted() {
                libraryRefresh.setEnabled(false);
                libraryStatus.setText("正在从相机读取照片元数据…");
            }

            @Override public void onProgress(int loaded, int total) {
                libraryStatus.setText(total <= 0 ? "相机中没有可识别的图片" : "已读取元数据 " + loaded + " / " + total + " · 网格实时填充中");
            }

            @Override public void onBatch(List<CameraMediaEntry> entries) {
                applyLibrarySnapshot(entries, false);
            }

            @Override public void onLoaded(List<CameraMediaEntry> entries) {
                libraryRefresh.setEnabled(true);
                applyLibrarySnapshot(entries, true);
                updateLibraryButtons();
            }

            @Override public void onError(String error) {
                libraryRefresh.setEnabled(true);
                libraryStatus.setText(error);
                Toast.makeText(getApplicationContext(), error, Toast.LENGTH_LONG).show();
                updateLibraryButtons();
            }
        });
    }

    /** Merge JPEG+RAW pairs off the UI thread, then swap the grid contents atomically. */
    private void applyLibrarySnapshot(List<CameraMediaEntry> entries, boolean finalBatch) {
        final int token = ++libraryMergeToken;
        final List<CameraMediaEntry> snapshot = new ArrayList<>(entries);
        exports.execute(() -> {
            final List<CameraAlbumItem> merged = CameraAlbumItem.mergePairs(snapshot);
            runOnUiThread(() -> {
                if (token != libraryMergeToken) return;
                libraryEntries.clear();
                libraryEntries.addAll(snapshot);
                libraryItems.clear();
                libraryItems.addAll(merged);
                libraryAdapter.replace(libraryItems);
                if (activeDownloadTask == null) {
                    libraryStatus.setText(libraryItems.isEmpty()
                            ? (finalBatch ? "未发现可浏览的 JPEG / RAW" : "正在从相机读取照片元数据…")
                            : finalBatch
                            ? "共 " + libraryItems.size() + " 组 / " + libraryEntries.size() + " 个文件 · 点击查看大图，长按下载原图"
                            : "已加载 " + libraryItems.size() + " 组，持续读取中…");
                }
                prefetchVisibleThumbnails(libraryGrid.getFirstVisiblePosition(), libraryGrid.getChildCount());
                updateLibrarySelectionUi();
                if (viewerOverlay != null && viewerOverlay.getVisibility() == View.VISIBLE) updateViewerChrome();
            });
        });
    }

    private void requestThumbnailForItem(CameraAlbumItem item) {
        if (item == null) return;
        CameraMediaEntry cover = item.cover();
        if (cover == null || cover.thumbnail != null) return;
        if (failedThumbnailHandles.contains(cover.handle)) return;
        if (!pendingThumbnailHandles.add(cover.handle)) return;
        controller.loadThumbnail(cover, new CameraController.ThumbnailCallback() {
            @Override public void onLoaded(CameraMediaEntry entry, Bitmap bitmap) {
                pendingThumbnailHandles.remove(entry.handle);
                if (bitmap == null) { failedThumbnailHandles.add(entry.handle); return; }
                libraryAdapter.notifyDataSetChanged();
                if (viewerOverlay.getVisibility() == View.VISIBLE && viewerItem != null && viewerItem.cover() != null
                        && viewerItem.cover().handle == entry.handle && viewerLargeHandle != entry.handle) {
                    viewerPreview.setBitmap(bitmap);
                }
            }

            @Override public void onError(CameraMediaEntry entry, String message) {
                pendingThumbnailHandles.remove(entry.handle);
                // Remember failures so the grid stops re-requesting files the camera cannot serve.
                if (entry.thumbnail == null) failedThumbnailHandles.add(entry.handle);
            }
        });
    }

    private void prefetchVisibleThumbnails(int firstVisibleItem, int visibleItemCount) {
        int end = Math.min(libraryAdapter.getCount(), firstVisibleItem + Math.max(visibleItemCount, 0) + 12);
        for (int position = Math.max(0, firstVisibleItem); position < end; position++)
            requestThumbnailForItem(libraryAdapter.itemAt(position));
    }

    private void updateLibrarySelectionUi() {
        libraryAdapter.setSelectedIds(selectedAlbumIds);
        int groups = selectedAlbumIds.size();
        int files = selectedEntries().size();
        librarySelection.setText(groups == 0 ? "未选择照片"
                : "已选择 " + groups + " 组 / " + files + " 个文件");
        updateLibraryButtons();
    }

    private void updateLibraryButtons() {
        boolean visible = libraryOverlay != null && libraryOverlay.getVisibility() == View.VISIBLE;
        boolean connected = controller != null && controller.connected();
        boolean hasEntries = !libraryItems.isEmpty();
        if (libraryRefresh != null) libraryRefresh.setEnabled(connected);
        if (librarySelectAll != null) librarySelectAll.setEnabled(hasEntries);
        if (libraryClear != null) libraryClear.setEnabled(!selectedAlbumIds.isEmpty());
        if (libraryDownloadSelected != null) libraryDownloadSelected.setEnabled(connected && !selectedAlbumIds.isEmpty());
        if (libraryChooseFolder != null) libraryChooseFolder.setEnabled(visible);
        if (libraryFolder != null) libraryFolder.setText(downloadFolderUri == null ? "下载目录：未选择" : "下载目录：" + folderLabel(downloadFolderUri));
        updateViewerButtons();
    }

    private List<CameraMediaEntry> selectedEntries() {
        if (selectedAlbumIds.isEmpty()) return Collections.emptyList();
        List<CameraMediaEntry> entries = new ArrayList<>();
        for (CameraAlbumItem item : libraryItems) if (selectedAlbumIds.contains(item.id)) entries.addAll(item.assets);
        return entries;
    }

    private String selectedTitle() {
        int groups = selectedAlbumIds.size();
        return groups == 0 ? "已选照片" : "已选 " + groups + " 组";
    }

    /** Enqueues a download job, shows a visible "joined queue" confirmation and starts draining. */
    private void requestDownload(List<CameraMediaEntry> entries, String title) {
        if (entries == null || entries.isEmpty()) {
            Toast.makeText(this, "请先选择要下载的照片", Toast.LENGTH_SHORT).show();
            return;
        }
        if (downloadFolderUri == null) {
            ArrayList<String> ids = new ArrayList<>();
            for (CameraAlbumItem item : libraryItems) {
                for (CameraMediaEntry entry : entries) {
                    if (item.assets.contains(entry)) { ids.add(item.id); break; }
                }
            }
            pendingDownloadTitle = title;
            pickDownloadFolder(ids);
            return;
        }
        String jobTitle = title == null || title.isEmpty() ? (entries.size() + " 个文件") : title;
        long totalBytes = 0;
        for (CameraMediaEntry entry : entries) totalBytes += Math.max(0, entry.sizeBytes);
        DownloadTask task = new DownloadTask("task-" + (++downloadTaskSeq), jobTitle, new ArrayList<>(entries));
        task.totalBytes = totalBytes;
        task.fileTotal = entries.size();
        downloadTasks.add(task);
        int queuedAhead = 0;
        for (DownloadTask other : downloadTasks) if (other.state == DownloadTask.State.QUEUED) queuedAhead++;
        refreshDownloadUi();
        Toast.makeText(getApplicationContext(), "已加入下载队列：" + jobTitle
                + (queuedAhead > 1 ? "（前面还有 " + (queuedAhead - 1) + " 个任务）" : ""), Toast.LENGTH_LONG).show();
        if (libraryStatus != null) libraryStatus.setText("已加入下载队列：" + jobTitle + " · " + CameraMediaEntry.humanBytes(totalBytes));
        // The in-gallery progress strip is hidden behind the viewer, so surface the queue instead.
        if (libraryOverlay.getVisibility() != View.VISIBLE) openDownloads();
        startNextDownload();
    }

    private void startNextDownload() {
        if (activeDownloadTask != null) return;
        DownloadTask task = null;
        for (DownloadTask candidate : downloadTasks) {
            if (candidate.state == DownloadTask.State.QUEUED) { task = candidate; break; }
        }
        if (task == null) { refreshDownloadUi(); return; }
        final DownloadTask job = task;
        activeDownloadTask = job;
        job.state = DownloadTask.State.RUNNING;
        refreshDownloadUi();
        controller.downloadMedia(downloadFolderUri, job.entries, new CameraController.DownloadCallback() {
            @Override public void onStarted(int totalFiles, long totalBytes) {
                job.fileTotal = totalFiles;
                job.totalBytes = Math.max(totalBytes, job.totalBytes);
                job.stage = "开始传输";
                refreshDownloadUi();
            }

            @Override public void onFileStarted(int index, int totalFiles, String filename, long fileBytes, long totalBytes) {
                job.fileIndex = index;
                job.fileTotal = totalFiles;
                job.currentFile = filename;
                job.stage = "读取中";
                refreshDownloadUi();
            }

            @Override public void onFileProgress(int index, int totalFiles, String filename, long fileProgressBytes, long fileBytes,
                                                 long completedBytes, long totalBytes, long bytesPerSecond, String stage) {
                job.fileIndex = index;
                job.fileTotal = totalFiles;
                job.currentFile = filename;
                job.fileBytes = fileBytes;
                job.fileProgressBytes = fileProgressBytes;
                job.completedBytes = completedBytes;
                job.totalBytes = Math.max(totalBytes, job.totalBytes);
                job.bytesPerSecond = bytesPerSecond;
                job.stage = stage;
                refreshDownloadUi();
            }

            @Override public void onCompleted(int successCount, int totalFiles, long totalBytes, List<String> errors) {
                job.errors.clear();
                if (errors != null) job.errors.addAll(errors);
                if (job.state != DownloadTask.State.CANCELLED) {
                    job.state = errors == null || errors.isEmpty() ? DownloadTask.State.DONE : DownloadTask.State.FAILED;
                }
                job.completedBytes = Math.max(totalBytes, job.totalBytes);
                activeDownloadTask = null;
                refreshDownloadUi();
                Toast.makeText(getApplicationContext(),
                        job.state == DownloadTask.State.DONE ? "下载完成：" + job.title
                                : job.state == DownloadTask.State.CANCELLED ? "已取消：" + job.title
                                : "下载结束：" + job.title + "，失败 " + job.errors.size() + " 个文件",
                        Toast.LENGTH_LONG).show();
                startNextDownload();
            }

            @Override public void onError(String message) {
                if (job.state != DownloadTask.State.CANCELLED) job.state = DownloadTask.State.FAILED;
                if (message != null && !message.isEmpty()) job.errors.add(message);
                activeDownloadTask = null;
                refreshDownloadUi();
                Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
                startNextDownload();
            }
        });
    }

    private void pickDownloadFolder(ArrayList<String> albumIdsAfterPick) {
        pendingDownloadAlbumIds = albumIdsAfterPick;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_DOWNLOAD_FOLDER);
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
        if (requestCode == EXPORT_PREVIEW) {
            Bitmap bitmap = exportBitmap;
            exportBitmap = null;
            if (resultCode != RESULT_OK || data == null || data.getData() == null || bitmap == null) return;
            Uri uri = data.getData();
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
            return;
        }
        if (requestCode == PICK_DOWNLOAD_FOLDER) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                pendingDownloadAlbumIds = null;
                return;
            }
            downloadFolderUri = data.getData();
            final int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try { getContentResolver().takePersistableUriPermission(downloadFolderUri, flags); } catch (RuntimeException ignored) { }
            getPreferences(MODE_PRIVATE).edit().putString("download_tree_uri", downloadFolderUri.toString()).apply();
            updateLibraryButtons();
            if (pendingDownloadAlbumIds != null && !pendingDownloadAlbumIds.isEmpty()) {
                Set<String> ids = new LinkedHashSet<>(pendingDownloadAlbumIds);
                pendingDownloadAlbumIds = null;
                List<CameraMediaEntry> entries = new ArrayList<>();
                for (CameraAlbumItem item : libraryItems) if (ids.contains(item.id)) entries.addAll(item.assets);
                String title = pendingDownloadTitle != null ? pendingDownloadTitle : selectedTitle();
                pendingDownloadTitle = null;
                requestDownload(entries, title);
            }
        }
    }

    private String folderLabel(Uri treeUri) {
        try {
            String id = DocumentsContract.getTreeDocumentId(treeUri);
            int slash = id.lastIndexOf(':');
            return slash >= 0 && slash < id.length() - 1 ? id.substring(slash + 1) : id;
        } catch (RuntimeException e) {
            return treeUri.toString();
        }
    }

    private void handleUsbIntent(Intent intent) {
        UsbDevice device = CameraController.deviceExtra(intent);
        if (device != null && CameraController.findInterface(device) != null
                && controller.devices().stream().anyMatch(d -> d.getDeviceName().equals(device.getDeviceName()))) controller.connect(device);
    }

    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handleUsbIntent(intent); }

    @Override public void onBackPressed() {
        if (downloadOverlay != null && downloadOverlay.getVisibility() == View.VISIBLE) { downloadOverlay.setVisibility(View.GONE); return; }
        if (viewerOverlay != null && viewerOverlay.getVisibility() == View.VISIBLE) { closeViewer(); return; }
        if (libraryOverlay != null && libraryOverlay.getVisibility() == View.VISIBLE) { closeLibrary(); return; }
        super.onBackPressed();
    }
    @Override protected void onResume() { super.onResume(); resumed = true; controller.setForeground(true); controller.observe(this); }

    @Override protected void onPause() {
        resumed = false;
        if (!isChangingConfigurations()) controller.setForeground(false);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onPause();
    }

    @Override protected void onStop() {
        super.onStop();
    }

    @Override public Object onRetainNonConfigurationInstance() { controller.observe(null); return controller; }

    @Override protected void onDestroy() {
        if (!isChangingConfigurations() && !controller.hasBackgroundWork()) controller.close();
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
