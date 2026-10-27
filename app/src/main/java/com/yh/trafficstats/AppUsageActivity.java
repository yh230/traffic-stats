package com.yh.trafficstats;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 应用流量页：按本期周期展示各应用移动网络 / WiFi 用量
 *
 * 数据每次进入页面时从系统按 UID 查询，不写入本地数据库；同一 UID 下的多个应用会合并，
 * 无法关联应用的 UID 合并到「系统与其他」。
 *
 * @author yh
 * @date 2026-10-26
 */
public class AppUsageActivity extends Activity {

    private final List<AppUsage> allUsage = new ArrayList<>();
    private final List<AppUsage> displayedUsage = new ArrayList<>();

    private TrafficCollector collector;
    private AppUsageAdapter adapter;
    private TextView periodView;
    private TextView totalValuesView;
    private TextView countView;
    private TextView stateView;
    private Button mobileTabButton;
    private Button wifiTabButton;
    private Button retryButton;
    private Button refreshButton;

    /** 当前是否展示移动网络；false 表示 WiFi */
    private boolean showMobile = true;

    /** 当前界面是否处于前台，忽略退到后台后的异步回调 */
    private boolean active;

    /** 加载序号，用于忽略过期的查询结果 */
    private int loadToken;

    /** 是否正在等待查询结果 */
    private boolean loading;

    /** 最近一次查询是否失败，失败时切换网络不清除错误提示 */
    private boolean loadFailed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_usage);

        collector = TrafficCollector.getInstance(this);
        periodView = findViewById(R.id.tv_app_usage_period);
        totalValuesView = findViewById(R.id.tv_app_usage_total_values);
        countView = findViewById(R.id.tv_app_usage_count);
        stateView = findViewById(R.id.tv_app_usage_state);
        mobileTabButton = findViewById(R.id.btn_tab_mobile);
        wifiTabButton = findViewById(R.id.btn_tab_wifi);
        retryButton = findViewById(R.id.btn_app_usage_retry);
        refreshButton = findViewById(R.id.btn_app_usage_refresh);
        ListView usageListView = findViewById(R.id.list_app_usage);

        adapter = new AppUsageAdapter();
        usageListView.setAdapter(adapter);
        usageListView.setEmptyView(findViewById(R.id.app_usage_state));

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        mobileTabButton.setOnClickListener(v -> selectNetwork(true));
        wifiTabButton.setOnClickListener(v -> selectNetwork(false));
        retryButton.setOnClickListener(v -> loadAppUsage());
        refreshButton.setOnClickListener(v -> loadAppUsage());

        updateTabSelection();
    }

    @Override
    protected void onResume() {
        super.onResume();
        active = true;
        loadAppUsage();
    }

    @Override
    protected void onPause() {
        active = false;
        super.onPause();
    }

    /**
     * 切换移动网络 / WiFi 视图
     *
     * @param mobile 是否展示移动网络
     */
    private void selectNetwork(boolean mobile) {
        if (showMobile == mobile) {
            return;
        }
        showMobile = mobile;
        updateTabSelection();
        if (!loading && !loadFailed) {
            applyNetworkFilter();
        }
    }

    /**
     * 刷新两个网络类型的选中状态
     */
    private void updateTabSelection() {
        mobileTabButton.setSelected(showMobile);
        wifiTabButton.setSelected(!showMobile);
    }

    /**
     * 异步读取本期各应用流量
     */
    private void loadAppUsage() {
        if (!active) {
            return;
        }
        int token = ++loadToken;
        showLoadingState();
        collector.loadAppUsage(new TrafficCollector.AppUsageListener() {
            @Override
            public void onAppUsageLoaded(List<AppUsage> appUsageList, long periodStartTime) {
                if (!active || token != loadToken) {
                    return;
                }
                loading = false;
                loadFailed = false;
                setRefreshEnabled(true);
                periodView.setText(getString(R.string.app_usage_period,
                        TrafficFormatter.formatPeriodTime(periodStartTime)));
                allUsage.clear();
                allUsage.addAll(appUsageList);
                applyNetworkFilter();
            }

            @Override
            public void onAppUsageFailed(String reason) {
                if (!active || token != loadToken) {
                    return;
                }
                loading = false;
                loadFailed = true;
                setRefreshEnabled(true);
                showErrorState(reason == null || reason.isEmpty()
                        ? getString(R.string.app_usage_load_failed) : reason);
            }
        });
    }

    /**
     * 进入加载状态并清空旧列表
     */
    private void showLoadingState() {
        loading = true;
        loadFailed = false;
        setRefreshEnabled(false);
        allUsage.clear();
        displayedUsage.clear();
        adapter.notifyDataSetChanged();
        periodView.setText(R.string.app_usage_period_unknown);
        totalValuesView.setText(R.string.summary_placeholder);
        countView.setText(getString(R.string.app_usage_count, 0));
        stateView.setText(R.string.app_usage_loading);
        stateView.setTextColor(getColor(R.color.text_secondary));
        retryButton.setVisibility(View.GONE);
    }

    /**
     * 切换刷新按钮的可用状态
     *
     * @param enabled 是否可用
     */
    private void setRefreshEnabled(boolean enabled) {
        refreshButton.setEnabled(enabled);
        refreshButton.setAlpha(enabled ? 1f : 0.5f);
    }

    /**
     * 展示查询失败状态
     *
     * @param message 用户可读的失败原因
     */
    private void showErrorState(String message) {
        allUsage.clear();
        displayedUsage.clear();
        adapter.notifyDataSetChanged();
        periodView.setText(R.string.app_usage_period_unknown);
        totalValuesView.setText(R.string.summary_placeholder);
        countView.setText(getString(R.string.app_usage_count, 0));
        stateView.setText(message);
        stateView.setTextColor(getColor(R.color.error));
        retryButton.setVisibility(View.VISIBLE);
    }

    /**
     * 按当前网络类型过滤、排序并更新合计
     */
    private void applyNetworkFilter() {
        displayedUsage.clear();
        long totalRxBytes = 0L;
        long totalTxBytes = 0L;
        for (AppUsage usage : allUsage) {
            if (usage.getTotalBytes(showMobile) > 0L) {
                displayedUsage.add(usage);
            }
        }
        displayedUsage.sort((first, second) -> Long.compare(
                second.getTotalBytes(showMobile), first.getTotalBytes(showMobile)));
        for (AppUsage usage : displayedUsage) {
            totalRxBytes += usage.getRxBytes(showMobile);
            totalTxBytes += usage.getTxBytes(showMobile);
        }
        adapter.notifyDataSetChanged();
        totalValuesView.setText(getString(R.string.app_usage_total_values,
                TrafficFormatter.formatBytes(totalRxBytes),
                TrafficFormatter.formatBytes(totalTxBytes)));
        countView.setText(getString(R.string.app_usage_count, displayedUsage.size()));
        retryButton.setVisibility(View.GONE);
        if (displayedUsage.isEmpty()) {
            stateView.setText(showMobile
                    ? R.string.app_usage_empty_mobile : R.string.app_usage_empty_wifi);
            stateView.setTextColor(getColor(R.color.text_secondary));
        }
    }

    /**
     * 应用流量列表适配器
     */
    private final class AppUsageAdapter extends BaseAdapter {

        private final LayoutInflater inflater = LayoutInflater.from(AppUsageActivity.this);

        @Override
        public int getCount() {
            return displayedUsage.size();
        }

        @Override
        public AppUsage getItem(int position) {
            return displayedUsage.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_app_usage, parent, false);
                holder = new ViewHolder();
                holder.iconView = convertView.findViewById(R.id.iv_app_icon);
                holder.labelView = convertView.findViewById(R.id.tv_app_label);
                holder.detailView = convertView.findViewById(R.id.tv_app_detail);
                holder.totalView = convertView.findViewById(R.id.tv_app_total);
                holder.breakdownView = convertView.findViewById(R.id.tv_app_breakdown);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            AppUsage usage = getItem(position);
            long rxBytes = usage.getRxBytes(showMobile);
            long txBytes = usage.getTxBytes(showMobile);
            holder.iconView.setImageDrawable(usage.getIcon());
            holder.labelView.setText(usage.getLabel());
            holder.detailView.setText(usage.getDetail());
            holder.totalView.setText(TrafficFormatter.formatBytes(rxBytes + txBytes));
            holder.breakdownView.setText(getString(R.string.app_usage_breakdown,
                    TrafficFormatter.formatBytes(rxBytes),
                    TrafficFormatter.formatBytes(txBytes)));
            return convertView;
        }
    }

    /**
     * 应用流量列表行视图缓存
     */
    private static final class ViewHolder {
        ImageView iconView;
        TextView labelView;
        TextView detailView;
        TextView totalView;
        TextView breakdownView;
    }
}
