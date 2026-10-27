package com.yh.trafficstats;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 主界面：权限引导、采集入口和本地历史列表
 *
 * 采集任务由 {@link TrafficCollector} 在进程内执行，Activity 只负责界面状态和结果展示；
 * 系统统计存在延迟，应用进程被杀死时采集会中断，结果仅供自用参考。
 *
 * @author yh
 * @date 2026-10-26
 */
public class MainActivity extends Activity implements TrafficCollector.Listener {

    private View permissionSection;
    private View mainHeader;
    private View mainHistoryHeader;
    private View mainListContainer;
    private TextView statusView;
    private TextView emptyView;
    private TextView periodStartView;
    private TextView mobileSummaryView;
    private TextView wifiSummaryView;
    private Button collectButton;
    private Button resetStatsButton;
    private RecordAdapter recordAdapter;
    private TrafficCollector collector;

    /** 当前界面是否处于前台，重建或退到后台时忽略异步回调 */
    private boolean active;

    private final List<TrafficRecord> records = new ArrayList<>();
    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        permissionSection = findViewById(R.id.permission_section);
        mainHeader = findViewById(R.id.main_header);
        mainHistoryHeader = findViewById(R.id.main_history_header);
        mainListContainer = findViewById(R.id.main_list_container);
        findViewById(R.id.iv_brand).setClipToOutline(true);
        statusView = findViewById(R.id.tv_status);
        emptyView = findViewById(R.id.tv_empty);
        periodStartView = findViewById(R.id.tv_period_start);
        mobileSummaryView = findViewById(R.id.tv_summary_mobile);
        wifiSummaryView = findViewById(R.id.tv_summary_wifi);
        collectButton = findViewById(R.id.btn_collect);
        resetStatsButton = findViewById(R.id.btn_reset_stats);
        ListView recordListView = findViewById(R.id.list_records);

        collector = TrafficCollector.getInstance(this);
        recordAdapter = new RecordAdapter();
        recordListView.setAdapter(recordAdapter);
        recordListView.setEmptyView(emptyView);

        findViewById(R.id.btn_open_usage_access).setOnClickListener(v -> openUsageAccessSettings());
        collectButton.setOnClickListener(v -> collectTraffic());
        resetStatsButton.setOnClickListener(v -> confirmResetStats());
        findViewById(R.id.btn_app_usage).setOnClickListener(v -> openAppUsage());
    }

    @Override
    protected void onResume() {
        super.onResume();
        active = true;
        refreshPermissionUi();
        // 放在刷新之后，回放的失败提示不会被状态文本覆盖
        collector.setListener(this);
    }

    @Override
    protected void onStop() {
        active = false;
        collector.clearListener(this);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        active = false;
        collector.clearListener(this);
        super.onDestroy();
    }

    @Override
    public void onCollectSuccess() {
        if (!active) {
            return;
        }
        collectButton.setEnabled(true);
        resetStatsButton.setEnabled(true);
        setStatus(R.string.collect_success, R.color.success);
        Toast.makeText(this, R.string.collect_success, Toast.LENGTH_SHORT).show();
        loadRecordsAsync();
        loadSummaryAsync();
    }

    @Override
    public void onCollectPermissionRevoked() {
        if (!active) {
            return;
        }
        refreshPermissionUi();
        Toast.makeText(this, R.string.collect_permission_revoked, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onCollectFailure(String reason) {
        if (!active) {
            return;
        }
        collectButton.setEnabled(true);
        resetStatsButton.setEnabled(true);
        showCollectError(reason == null || reason.isEmpty()
                ? getString(R.string.collect_failed_default) : reason);
    }

    /**
     * 根据「使用情况访问」权限切换权限引导页和采集页
     */
    private void refreshPermissionUi() {
        boolean granted = hasUsageAccess();
        permissionSection.setVisibility(granted ? View.GONE : View.VISIBLE);
        mainHeader.setVisibility(granted ? View.VISIBLE : View.GONE);
        mainHistoryHeader.setVisibility(granted ? View.VISIBLE : View.GONE);
        mainListContainer.setVisibility(granted ? View.VISIBLE : View.GONE);
        if (granted) {
            boolean busy = collector.isBusy();
            boolean collecting = collector.isCollecting();
            boolean resetting = collector.isResetting();
            collectButton.setEnabled(!busy);
            resetStatsButton.setEnabled(!busy);
            if (resetting) {
                setStatus(R.string.resetting, R.color.primary);
            } else if (collecting) {
                setStatus(R.string.collecting, R.color.primary);
            } else {
                setStatus(R.string.collect_hint, R.color.text_secondary);
            }
            loadRecordsAsync();
            loadSummaryAsync();
        }
    }

    /**
     * 检查「使用情况访问」权限
     *
     * PACKAGE_USAGE_STATS 无法通过运行时弹窗申请，只能通过 AppOps 查询用户手动授权的状态。
     *
     * @return 已授权返回 true
     */
    private boolean hasUsageAccess() {
        AppOpsManager appOpsManager = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        if (appOpsManager == null) {
            return false;
        }
        int mode = appOpsManager.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    /**
     * 打开系统「使用情况访问」设置页
     *
     * 优先定位到本应用；部分厂商系统不支持包名入口时回退到权限列表页。
     */
    private void openUsageAccessSettings() {
        Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException firstException) {
            try {
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            } catch (ActivityNotFoundException secondException) {
                Toast.makeText(this, R.string.open_settings_failed, Toast.LENGTH_LONG).show();
            }
        }
    }

    /**
     * 打开各应用用量页
     *
     * 应用页复用本期统计起点，查询系统当前周期的按 UID 用量。
     */
    private void openAppUsage() {
        if (!active) {
            return;
        }
        if (!hasUsageAccess()) {
            refreshPermissionUi();
            Toast.makeText(this, R.string.collect_permission_revoked, Toast.LENGTH_LONG).show();
            return;
        }
        startActivity(new Intent(this, AppUsageActivity.class));
    }

    /**
     * 请求采集器开始一次整机流量采集
     */
    private void collectTraffic() {
        if (!active) {
            return;
        }
        if (collector.isBusy()) {
            refreshPermissionUi();
            return;
        }
        if (!hasUsageAccess()) {
            refreshPermissionUi();
            Toast.makeText(this, R.string.collect_permission_revoked, Toast.LENGTH_LONG).show();
            return;
        }
        if (!collector.collect(this)) {
            refreshPermissionUi();
            return;
        }
        collectButton.setEnabled(false);
        resetStatsButton.setEnabled(false);
        setStatus(R.string.collecting, R.color.primary);
    }

    /**
     * 异步读取本地记录并刷新列表
     */
    private void loadRecordsAsync() {
        if (!active) {
            return;
        }
        emptyView.setText(R.string.loading_records);
        emptyView.setTextColor(getColor(R.color.text_secondary));
        collector.loadRecords(new TrafficCollector.RecordsCallback() {
            @Override
            public void onRecordsLoaded(List<TrafficRecord> loadedRecords) {
                if (active) {
                    applyRecords(loadedRecords);
                }
            }

            @Override
            public void onRecordsLoadFailed() {
                if (!active) {
                    return;
                }
                emptyView.setText(R.string.load_records_error_short);
                emptyView.setTextColor(getColor(R.color.error));
                setStatus(R.string.load_records_error_short, R.color.error);
            }
        });
    }

    /**
     * 在主线程更新列表数据
     *
     * @param latestRecords 最新的记录列表
     */
    private void applyRecords(List<TrafficRecord> latestRecords) {
        records.clear();
        records.addAll(latestRecords);
        emptyView.setTextColor(getColor(R.color.text_secondary));
        if (records.isEmpty()) {
            emptyView.setText(R.string.empty_records);
        }
        recordAdapter.notifyDataSetChanged();
    }

    /**
     * 异步读取本期累计并更新摘要
     */
    private void loadSummaryAsync() {
        if (!active) {
            return;
        }
        collector.loadSummary(new TrafficCollector.SummaryListener() {
            @Override
            public void onSummaryLoaded(TrafficCollector.Summary summary) {
                if (active) {
                    applySummary(summary);
                }
            }

            @Override
            public void onSummaryFailed(String reason) {
                if (!active) {
                    return;
                }
                periodStartView.setText(R.string.summary_load_failed);
                mobileSummaryView.setText(R.string.summary_placeholder);
                wifiSummaryView.setText(R.string.summary_placeholder);
            }
        });
    }

    /**
     * 在主线程展示本期累计
     *
     * @param summary 累计数据
     */
    private void applySummary(TrafficCollector.Summary summary) {
        periodStartView.setText(getString(R.string.period_start_format,
                TrafficFormatter.formatPeriodTime(summary.periodStartTime)));
        mobileSummaryView.setText(getString(R.string.summary_network_values,
                TrafficFormatter.formatBytes(summary.mobileRxBytes),
                TrafficFormatter.formatBytes(summary.mobileTxBytes)));
        wifiSummaryView.setText(getString(R.string.summary_network_values,
                TrafficFormatter.formatBytes(summary.wifiRxBytes),
                TrafficFormatter.formatBytes(summary.wifiTxBytes)));
    }

    /**
     * 显示重置确认框
     */
    private void confirmResetStats() {
        if (!active) {
            return;
        }
        if (collector.isBusy()) {
            refreshPermissionUi();
            Toast.makeText(this, R.string.reset_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.reset_confirm_title)
                .setMessage(R.string.reset_confirm_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm_reset,
                        (confirmDialog, which) -> performResetStats())
                .create();
        dialog.setOnShowListener(shownDialog -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setTextColor(getColor(R.color.error)));
        dialog.show();
    }

    /**
     * 执行重置统计
     */
    private void performResetStats() {
        if (!active) {
            return;
        }
        collectButton.setEnabled(false);
        resetStatsButton.setEnabled(false);
        setStatus(R.string.resetting, R.color.primary);
        boolean started = collector.resetStats(new TrafficCollector.ResetListener() {
            @Override
            public void onResetSuccess(long periodStartTime) {
                if (!active) {
                    return;
                }
                periodStartView.setText(getString(R.string.period_start_format,
                        TrafficFormatter.formatPeriodTime(periodStartTime)));
                mobileSummaryView.setText(getString(R.string.summary_network_values,
                        TrafficFormatter.formatBytes(0L), TrafficFormatter.formatBytes(0L)));
                wifiSummaryView.setText(getString(R.string.summary_network_values,
                        TrafficFormatter.formatBytes(0L), TrafficFormatter.formatBytes(0L)));
                records.clear();
                recordAdapter.notifyDataSetChanged();
                emptyView.setText(R.string.empty_records);
                emptyView.setTextColor(getColor(R.color.text_secondary));
                collectButton.setEnabled(true);
                resetStatsButton.setEnabled(true);
                setStatus(R.string.reset_success, R.color.success);
            }

            @Override
            public void onResetFailed(String reason) {
                if (!active) {
                    return;
                }
                collectButton.setEnabled(true);
                resetStatsButton.setEnabled(true);
                showCollectError(reason == null || reason.isEmpty()
                        ? getString(R.string.collect_failed_default) : reason);
            }
        });
        if (!started) {
            refreshPermissionUi();
            Toast.makeText(this, R.string.reset_busy, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 在页面和 Toast 中展示采集失败原因
     *
     * @param message 用户可读的失败原因
     */
    private void showCollectError(String message) {
        statusView.setText(message);
        statusView.setTextColor(getColor(R.color.error));
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    /**
     * 设置状态文本及对应语义色
     *
     * @param textResId 文本资源
     * @param colorResId 颜色资源
     */
    private void setStatus(int textResId, int colorResId) {
        statusView.setText(textResId);
        statusView.setTextColor(getColor(colorResId));
    }

    /**
     * 格式化采集时间
     *
     * @param timeMillis Unix 毫秒时间戳
     * @return 本地时区的时间文本
     */
    private String formatTime(long timeMillis) {
        return dateFormat.format(new Date(timeMillis));
    }

    /**
     * 历史记录列表适配器
     */
    private class RecordAdapter extends BaseAdapter {

        private final LayoutInflater inflater = LayoutInflater.from(MainActivity.this);

        @Override
        public int getCount() {
            return records.size();
        }

        @Override
        public TrafficRecord getItem(int position) {
            return records.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.item_traffic_record, parent, false);
                holder = new ViewHolder();
                holder.timeView = convertView.findViewById(R.id.tv_record_time);
                holder.typeView = convertView.findViewById(R.id.tv_network_type);
                holder.rxValueView = convertView.findViewById(R.id.tv_rx_value);
                holder.txValueView = convertView.findViewById(R.id.tv_tx_value);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            TrafficRecord record = getItem(position);
            boolean mobile = TrafficDbHelper.NETWORK_TYPE_MOBILE.equals(record.getNetworkType());
            holder.timeView.setText(formatTime(record.getRecordTime()));
            holder.typeView.setText(mobile ? R.string.network_mobile : R.string.network_wifi);
            holder.typeView.setBackgroundResource(
                    mobile ? R.drawable.bg_badge_mobile : R.drawable.bg_badge_wifi);
            holder.typeView.setTextColor(
                    getColor(mobile ? R.color.badge_mobile_text : R.color.badge_wifi_text));
            holder.rxValueView.setText(TrafficFormatter.formatBytes(record.getRxBytes()));
            holder.txValueView.setText(TrafficFormatter.formatBytes(record.getTxBytes()));
            return convertView;
        }
    }

    /**
     * 列表行视图缓存
     */
    private static final class ViewHolder {
        TextView timeView;
        TextView typeView;
        TextView rxValueView;
        TextView txValueView;
    }
}
