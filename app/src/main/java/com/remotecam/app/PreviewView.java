package com.remotecam.app;

import android.content.Context;
import android.graphics.*;
import android.view.*;

/** Letterboxed preview with image-space focus coordinates and non-destructive review zoom. */
final class PreviewView extends View {
    interface FocusListener { void onFocus(float x, float y); }

    /** Fired when the user swipes the viewer; true = next photo, false = previous photo. */
    interface SwipeListener { void onSwipe(boolean next); }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private SwipeListener swipeListener;
    private final RectF imageRect = new RectF();
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestures;
    private Bitmap bitmap;
    private float zoom = 1f;
    private float panX, panY;
    private boolean grid;
    private boolean review;
    private FocusListener focus;
    private String title = "你的下一张作品，从这里开始";
    private String subtitle = "USB 连接 · Canon / Nikon";

    PreviewView(Context context) {
        super(context);
        setContentDescription("相机预览。实时取景时轻点设置焦点，照片预览支持双指缩放和双击复位");
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                if (!review) return false;
                zoom = Math.max(1f, Math.min(6f, zoom * detector.getScaleFactor()));
                if (zoom == 1f) panX = panY = 0;
                invalidate();
                return true;
            }
        });
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                performClick();
                if (!review && focus != null && bitmap != null && imageRect.contains(e.getX(), e.getY()))
                    focus.onFocus((e.getX() - imageRect.left) / imageRect.width(), (e.getY() - imageRect.top) / imageRect.height());
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                zoom = 1f; panX = panY = 0; invalidate(); return true;
            }
            @Override public boolean onFling(MotionEvent first, MotionEvent current, float velocityX, float velocityY) {
                if (!review || zoom > 1f || swipeListener == null || first == null || current == null) return false;
                float dx = current.getX() - first.getX();
                float dy = current.getY() - first.getY();
                float minDistance = 48 * getResources().getDisplayMetrics().density;
                if (Math.abs(dy) > Math.abs(dx)) {
                    if (Math.abs(dy) < minDistance || Math.abs(velocityY) < 400f) return false;
                    swipeListener.onSwipe(dy < 0);   // 上滑 → 下一张
                    return true;
                }
                if (Math.abs(dx) < minDistance || Math.abs(velocityX) < 400f) return false;
                swipeListener.onSwipe(dx < 0);       // 左滑 → 下一张
                return true;
            }
            @Override public boolean onScroll(MotionEvent first, MotionEvent current, float dx, float dy) {
                if (!review || zoom <= 1f || scaleDetector.isInProgress()) return false;
                panX = Math.max(-getWidth() * (zoom - 1) / 2, Math.min(getWidth() * (zoom - 1) / 2, panX - dx));
                panY = Math.max(-getHeight() * (zoom - 1) / 2, Math.min(getHeight() * (zoom - 1) / 2, panY - dy));
                invalidate(); return true;
            }
        });
    }
    void setBitmap(Bitmap bitmap) { this.bitmap = bitmap; invalidate(); }
    void setReview(boolean review) { this.review = review; zoom = 1f; panX = panY = 0; }
    void setGrid(boolean grid) { this.grid = grid; invalidate(); }
    void setFocusListener(FocusListener listener) { focus = listener; }
    void setSwipeListener(SwipeListener listener) { swipeListener = listener; }
    void setPlaceholder(String title, String subtitle) { this.title = title; this.subtitle = subtitle; invalidate(); }
    @Override public boolean performClick() { super.performClick(); return true; }
    @Override public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestures.onTouchEvent(event);
        if (review && (zoom > 1 || event.getPointerCount() > 1)) getParent().requestDisallowInterceptTouchEvent(true);
        return true;
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.rgb(8, 12, 17));
        if (bitmap == null || bitmap.isRecycled()) {
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(Color.rgb(93, 224, 188));
            paint.setTextSize(42 * getResources().getDisplayMetrics().scaledDensity);
            canvas.drawText("◎", getWidth() / 2f, getHeight() / 2f - 30, paint);
            paint.setColor(Color.rgb(228, 235, 242));
            paint.setTextSize(16 * getResources().getDisplayMetrics().scaledDensity);
            canvas.drawText(title, getWidth() / 2f, getHeight() / 2f + 12, paint);
            paint.setColor(Color.rgb(145, 157, 175));
            paint.setTextSize(12 * getResources().getDisplayMetrics().scaledDensity);
            canvas.drawText(subtitle, getWidth() / 2f, getHeight() / 2f + 42, paint);
            return;
        }
        float scale = Math.min((float) getWidth() / bitmap.getWidth(), (float) getHeight() / bitmap.getHeight());
        float width = bitmap.getWidth() * scale, height = bitmap.getHeight() * scale;
        imageRect.set((getWidth() - width) / 2, (getHeight() - height) / 2,
                (getWidth() + width) / 2, (getHeight() + height) / 2);
        canvas.save();
        canvas.translate(getWidth() / 2f + panX, getHeight() / 2f + panY);
        canvas.scale(zoom, zoom);
        canvas.translate(-getWidth() / 2f, -getHeight() / 2f);
        canvas.drawBitmap(bitmap, null, imageRect, paint);
        canvas.restore();
        if (grid && !review) {
            paint.setColor(0x55FFFFFF); paint.setStrokeWidth(1);
            for (int i = 1; i < 3; i++) {
                float x = imageRect.left + imageRect.width() * i / 3;
                float y = imageRect.top + imageRect.height() * i / 3;
                canvas.drawLine(x, imageRect.top, x, imageRect.bottom, paint);
                canvas.drawLine(imageRect.left, y, imageRect.right, y, paint);
            }
        }
    }
}
