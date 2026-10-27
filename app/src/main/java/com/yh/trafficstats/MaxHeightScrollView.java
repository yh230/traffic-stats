package com.yh.trafficstats;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ScrollView;

/**
 * 最大高度受限的 ScrollView
 *
 * 用于页面头部：正常高度不滚动，窗口过短或字体过大时在限定高度内滚动，
 * 避免头部把下方的历史列表完全挤掉。
 *
 * @author yh
 * @date 2026-10-26
 */
public class MaxHeightScrollView extends ScrollView {

    /** 头部最多占屏幕高度的比例 */
    private static final float MAX_HEIGHT_RATIO = 0.45f;

    /**
     * @param context 上下文
     * @param attrs XML 属性
     */
    public MaxHeightScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int maxHeight = (int) (getResources().getDisplayMetrics().heightPixels * MAX_HEIGHT_RATIO);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);
        if (heightMode == MeasureSpec.UNSPECIFIED || heightSize > maxHeight) {
            heightMeasureSpec = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
