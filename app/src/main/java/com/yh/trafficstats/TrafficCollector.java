package com.yh.trafficstats;

import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.sqlite.SQLiteException;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 进程级流量采集器
 *
 * 采集与数据库操作在应用进程内串行执行，生命周期独立于 Activity；Activity 重建后任务继续，
 * 新界面通过回调接收结果。系统统计存在延迟；应用进程被杀死时任务会中断，结果仅供自用参考。
 *
 * @author yh
 * @date 2026-10-26
 */
public final class TrafficCollector {

    /**
     * 采集结果回调，均在主线程执行
     */
    public interface Listener {
        void onCollectSuccess();

        void onCollectPermissionRevoked();

        void onCollectFailure(String reason);
    }

    /**
     * 历史记录读取回调，均在主线程执行
     */
    public interface RecordsCallback {
        void onRecordsLoaded(List<TrafficRecord> records);

        void onRecordsLoadFailed();
    }

    /**
     * 本期累计查询回调，均在主线程执行
     */
    public interface SummaryListener {
        void onSummaryLoaded(Summary summary);

        void onSummaryFailed(String reason);
    }

    /**
     * 应用流量查询回调，均在主线程执行
     */
    public interface AppUsageListener {
        void onAppUsageLoaded(List<AppUsage> appUsageList, long periodStartTime);

        void onAppUsageFailed(String reason);
    }

    /**
     * 重置统计回调，均在主线程执行
     */
    public interface ResetListener {
        void onResetSuccess(long periodStartTime);

        void onResetFailed(String reason);
    }

    /**
     * 本期累计数据
     */
    public static final class Summary {
        /** 本期起点，Unix 毫秒时间戳 */
        public final long periodStartTime;

        /** 移动网络下载字节数 */
        public final long mobileRxBytes;

        /** 移动网络上传字节数 */
        public final long mobileTxBytes;

        /** WiFi 下载字节数 */
        public final long wifiRxBytes;

        /** WiFi 上传字节数 */
        public final long wifiTxBytes;

        /**
         * @param periodStartTime 本期起点
         * @param mobileRxBytes 移动网络下载字节数
         * @param mobileTxBytes 移动网络上传字节数
         * @param wifiRxBytes WiFi 下载字节数
         * @param wifiTxBytes WiFi 上传字节数
         */
        Summary(long periodStartTime,
                long mobileRxBytes, long mobileTxBytes,
                long wifiRxBytes, long wifiTxBytes) {
            this.periodStartTime = periodStartTime;
            this.mobileRxBytes = mobileRxBytes;
            this.mobileTxBytes = mobileTxBytes;
            this.wifiRxBytes = wifiRxBytes;
            this.wifiTxBytes = wifiTxBytes;
        }
    }

    private static final int RESULT_SUCCESS = 0;
    private static final int RESULT_PERMISSION_REVOKED = 1;
    private static final int RESULT_FAILURE = 2;

    /** 对应 ConnectivityManager.TYPE_MOBILE；NetworkStatsManager 的查询接口需要该常量 */
    @SuppressWarnings("deprecation")
    private static final int NETWORK_TYPE_MOBILE = ConnectivityManager.TYPE_MOBILE;

    /** 对应 ConnectivityManager.TYPE_WIFI */
    @SuppressWarnings("deprecation")
    private static final int NETWORK_TYPE_WIFI = ConnectivityManager.TYPE_WIFI;

    private static volatile TrafficCollector instance;

    private final Context appContext;
    private final TrafficDbHelper dbHelper;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object stateLock = new Object();

    private boolean collecting;
    private boolean resetting;
    private Listener listener;
    private PendingResult pendingResult;

    private TrafficCollector(Context context) {
        appContext = context.getApplicationContext();
        // 采集器随进程存活，数据库连接不随 Activity 重建关闭
        dbHelper = new TrafficDbHelper(appContext);
    }

    /**
     * 获取进程内唯一实例
     *
     * @param context 任意 Context
     * @return 采集器实例
     */
    public static TrafficCollector getInstance(Context context) {
        TrafficCollector local = instance;
        if (local == null) {
            synchronized (TrafficCollector.class) {
                local = instance;
                if (local == null) {
                    local = new TrafficCollector(context);
                    instance = local;
                }
            }
        }
        return local;
    }

    /**
     * @return 是否有采集任务正在执行
     */
    public boolean isCollecting() {
        synchronized (stateLock) {
            return collecting;
        }
    }

    /**
     * @return 是否正在重置统计
     */
    public boolean isResetting() {
        synchronized (stateLock) {
            return resetting;
        }
    }

    /**
     * @return 是否有采集或重置任务正在执行
     */
    public boolean isBusy() {
        synchronized (stateLock) {
            return collecting || resetting;
        }
    }

    /**
     * 注册当前界面的采集结果回调，并回放页面停止期间产生的失败结果
     *
     * @param newListener 当前界面
     */
    public void setListener(Listener newListener) {
        PendingResult pending;
        synchronized (stateLock) {
            listener = newListener;
            pending = !collecting && !resetting && pendingResult != null ? pendingResult : null;
            if (pending != null) {
                pendingResult = null;
            }
        }
        if (pending != null && newListener != null) {
            deliver(newListener, pending.result, pending.reason);
        }
    }

    /**
     * 清除当前界面的采集结果回调
     *
     * @param oldListener 要清除的界面回调
     */
    public void clearListener(Listener oldListener) {
        synchronized (stateLock) {
            if (listener == oldListener) {
                listener = null;
            }
        }
    }

    /**
     * 开始一次采集；已有任务执行中时返回 false
     *
     * @param newListener 采集结果回调
     * @return 是否成功开始新任务
     */
    public boolean collect(Listener newListener) {
        synchronized (stateLock) {
            if (collecting || resetting) {
                listener = newListener;
                return false;
            }
            collecting = true;
            listener = newListener;
            pendingResult = null;
        }
        executor.execute(this::doCollect);
        return true;
    }

    /**
     * 异步读取全部采集明细
     *
     * @param callback 主线程回调
     */
    public void loadRecords(RecordsCallback callback) {
        executor.execute(() -> {
            try {
                List<TrafficRecord> loadedRecords = dbHelper.queryAllRecords();
                mainHandler.post(() -> callback.onRecordsLoaded(loadedRecords));
            } catch (RuntimeException e) {
                mainHandler.post(callback::onRecordsLoadFailed);
            }
        });
    }

    /**
     * 异步读取本期累计流量
     *
     * @param callback 主线程回调
     */
    public void loadSummary(SummaryListener callback) {
        executor.execute(() -> {
            try {
                long periodStart = dbHelper.getPeriodStartTime();
                long now = System.currentTimeMillis();
                if (now < periodStart) {
                    throw new CollectException(
                            appContext.getString(R.string.collect_clock_error));
                }
                NetworkStatsManager statsManager = (NetworkStatsManager)
                        appContext.getSystemService(Context.NETWORK_STATS_SERVICE);
                if (statsManager == null) {
                    throw new CollectException(
                            appContext.getString(R.string.collect_failed_default));
                }
                TrafficAmount mobile = queryTraffic(
                        statsManager, NETWORK_TYPE_MOBILE, periodStart, now);
                TrafficAmount wifi = queryTraffic(
                        statsManager, NETWORK_TYPE_WIFI, periodStart, now);
                if (mobile == null || wifi == null) {
                    throw new CollectException(
                            appContext.getString(R.string.collect_no_valid_data));
                }
                Summary summary = new Summary(periodStart,
                        mobile.rxBytes, mobile.txBytes,
                        wifi.rxBytes, wifi.txBytes);
                mainHandler.post(() -> callback.onSummaryLoaded(summary));
            } catch (SecurityException e) {
                postSummaryFailure(callback,
                        appContext.getString(R.string.collect_permission_revoked));
            } catch (RemoteException | SQLiteException e) {
                postSummaryFailure(callback,
                        appContext.getString(R.string.collect_retry_error));
            } catch (CollectException e) {
                postSummaryFailure(callback, e.getMessage());
            } catch (RuntimeException e) {
                postSummaryFailure(callback,
                        appContext.getString(R.string.collect_failed_default));
            }
        });
    }

    /**
     * 异步查询本期各应用流量
     *
     * 移动网络和 WiFi 一次查询完成，由界面切换展示；结果只来自系统统计，不写入本地数据库。
     *
     * @param callback 主线程回调
     */
    public void loadAppUsage(AppUsageListener callback) {
        executor.execute(() -> {
            try {
                long periodStart = dbHelper.getPeriodStartTime();
                long now = System.currentTimeMillis();
                if (now < periodStart) {
                    throw new CollectException(
                            appContext.getString(R.string.collect_clock_error));
                }
                NetworkStatsManager statsManager = (NetworkStatsManager)
                        appContext.getSystemService(Context.NETWORK_STATS_SERVICE);
                if (statsManager == null) {
                    throw new CollectException(
                            appContext.getString(R.string.collect_failed_default));
                }
                Map<Integer, TrafficAmount> mobile = queryTrafficByUid(
                        statsManager, NETWORK_TYPE_MOBILE, periodStart, now);
                Map<Integer, TrafficAmount> wifi = queryTrafficByUid(
                        statsManager, NETWORK_TYPE_WIFI, periodStart, now);
                List<AppUsage> appUsageList = buildAppUsageList(mobile, wifi);
                mainHandler.post(() -> callback.onAppUsageLoaded(appUsageList, periodStart));
            } catch (SecurityException e) {
                postAppUsageFailure(callback,
                        appContext.getString(R.string.collect_permission_revoked));
            } catch (RemoteException | SQLiteException e) {
                postAppUsageFailure(callback,
                        appContext.getString(R.string.collect_retry_error));
            } catch (CollectException e) {
                postAppUsageFailure(callback, e.getMessage());
            } catch (RuntimeException e) {
                postAppUsageFailure(callback,
                        appContext.getString(R.string.app_usage_load_failed));
            }
        });
    }

    /**
     * 重置本期统计：清空采集明细并从当前时刻重新累计
     *
     * @param callback 主线程回调
     * @return 是否成功开始重置任务
     */
    public boolean resetStats(ResetListener callback) {
        synchronized (stateLock) {
            if (collecting || resetting) {
                return false;
            }
            resetting = true;
            pendingResult = null;
        }
        executor.execute(() -> {
            try {
                long periodStart = System.currentTimeMillis();
                dbHelper.resetPeriod(periodStart);
                mainHandler.post(() -> {
                    synchronized (stateLock) {
                        resetting = false;
                    }
                    callback.onResetSuccess(periodStart);
                });
            } catch (RuntimeException e) {
                mainHandler.post(() -> {
                    synchronized (stateLock) {
                        resetting = false;
                    }
                    callback.onResetFailed(
                            appContext.getString(R.string.collect_failed_default));
                });
            }
        });
        return true;
    }

    /**
     * 在采集线程中读取系统统计并写入数据库
     */
    private void doCollect() {
        boolean resultPosted = false;
        try {
            long now = System.currentTimeMillis();
            long periodStart = dbHelper.getPeriodStartTime();
            long mobileLastRecordTime =
                    dbHelper.getLastRecordTime(TrafficDbHelper.NETWORK_TYPE_MOBILE);
            long wifiLastRecordTime =
                    dbHelper.getLastRecordTime(TrafficDbHelper.NETWORK_TYPE_WIFI);
            long latestKnownTime = Math.max(periodStart,
                    Math.max(mobileLastRecordTime, wifiLastRecordTime));
            if (now < latestKnownTime) {
                throw new CollectException(appContext.getString(R.string.collect_clock_error));
            }

            long mobileStart = getQueryStartTime(mobileLastRecordTime, periodStart);
            long wifiStart = getQueryStartTime(wifiLastRecordTime, periodStart);
            NetworkStatsManager statsManager =
                    (NetworkStatsManager) appContext.getSystemService(Context.NETWORK_STATS_SERVICE);
            if (statsManager == null) {
                throw new CollectException(
                        appContext.getString(R.string.collect_failed_default));
            }

            TrafficAmount mobile = queryTraffic(
                    statsManager, NETWORK_TYPE_MOBILE, mobileStart, now);
            TrafficAmount wifi = queryTraffic(
                    statsManager, NETWORK_TYPE_WIFI, wifiStart, now);
            if (mobile == null || wifi == null) {
                throw new CollectException(
                        appContext.getString(R.string.collect_no_valid_data));
            }

            dbHelper.insertRecordPair(now,
                    mobile.rxBytes, mobile.txBytes,
                    wifi.rxBytes, wifi.txBytes);
            postResult(RESULT_SUCCESS, null);
            resultPosted = true;
        } catch (SecurityException e) {
            postResult(RESULT_PERMISSION_REVOKED, null);
            resultPosted = true;
        } catch (RemoteException | SQLiteException e) {
            postResult(RESULT_FAILURE, appContext.getString(R.string.collect_retry_error));
            resultPosted = true;
        } catch (CollectException e) {
            postResult(RESULT_FAILURE, e.getMessage());
            resultPosted = true;
        } catch (RuntimeException e) {
            postResult(RESULT_FAILURE, appContext.getString(R.string.collect_failed_default));
            resultPosted = true;
        } finally {
            if (!resultPosted) {
                postResult(RESULT_FAILURE, appContext.getString(R.string.collect_failed_default));
            }
        }
    }

    /**
     * 把累计查询失败结果切回主线程
     *
     * @param callback 界面回调
     * @param reason 失败原因
     */
    private void postSummaryFailure(SummaryListener callback, String reason) {
        mainHandler.post(() -> callback.onSummaryFailed(reason == null
                ? appContext.getString(R.string.collect_failed_default) : reason));
    }

    /**
     * 把应用流量查询失败结果切回主线程
     *
     * @param callback 界面回调
     * @param reason 失败原因
     */
    private void postAppUsageFailure(AppUsageListener callback, String reason) {
        mainHandler.post(() -> callback.onAppUsageFailed(reason == null
                ? appContext.getString(R.string.app_usage_load_failed) : reason));
    }

    /**
     * 查询指定网络类型在给定区间内的整机流量
     *
     * @param statsManager 系统流量统计服务
     * @param networkType 移动网络或 WiFi 的类型常量
     * @param startTime 统计起点，Unix 毫秒时间戳
     * @param endTime 统计终点，Unix 毫秒时间戳
     * @return 下载和上传字节数；系统未返回有效数据时返回 null
     * @throws RemoteException 调用系统服务失败
     */
    private TrafficAmount queryTraffic(NetworkStatsManager statsManager, int networkType,
            long startTime, long endTime) throws RemoteException {
        if (endTime <= startTime) {
            return new TrafficAmount(0L, 0L);
        }
        NetworkStats.Bucket bucket = statsManager.querySummaryForDevice(
                networkType, null, startTime, endTime);
        if (bucket == null) {
            return null;
        }
        long rxBytes = bucket.getRxBytes();
        long txBytes = bucket.getTxBytes();
        if (rxBytes < 0L || txBytes < 0L) {
            return null;
        }
        return new TrafficAmount(rxBytes, txBytes);
    }

    /**
     * 查询指定网络类型在给定区间内按 UID 聚合的流量
     *
     * @param statsManager 系统流量统计服务
     * @param networkType 移动网络或 WiFi 的类型常量
     * @param startTime 统计起点，Unix 毫秒时间戳
     * @param endTime 统计终点，Unix 毫秒时间戳
     * @return UID 到下载/上传字节数的映射
     * @throws RemoteException 调用系统服务失败
     */
    private Map<Integer, TrafficAmount> queryTrafficByUid(NetworkStatsManager statsManager,
            int networkType, long startTime, long endTime) throws RemoteException {
        Map<Integer, TrafficAmount> usageByUid = new HashMap<>();
        if (endTime <= startTime) {
            return usageByUid;
        }
        NetworkStats networkStats = statsManager.querySummary(
                networkType, null, startTime, endTime);
        if (networkStats == null) {
            return usageByUid;
        }
        try {
            NetworkStats.Bucket bucket = new NetworkStats.Bucket();
            while (networkStats.getNextBucket(bucket)) {
                int uid = bucket.getUid();
                long rxBytes = bucket.getRxBytes();
                long txBytes = bucket.getTxBytes();
                // 负值是不完整数据；UID_REMOVED / UID_TETHERING 等特殊 UID 会归入系统项
                if (rxBytes < 0L || txBytes < 0L) {
                    continue;
                }
                TrafficAmount current = usageByUid.get(uid);
                usageByUid.put(uid, current == null
                        ? new TrafficAmount(rxBytes, txBytes)
                        : new TrafficAmount(current.rxBytes + rxBytes,
                                current.txBytes + txBytes));
            }
        } finally {
            networkStats.close();
        }
        return usageByUid;
    }

    /**
     * 把按 UID 的查询结果转换为可展示的应用列表
     *
     * 无法关联到已安装应用的 UID 会合并为「系统与其他」一项，避免列表充满无意义条目。
     *
     * @param mobile 移动网络按 UID 聚合的用量
     * @param wifi WiFi 按 UID 聚合的用量
     * @return 应用与系统项的流量列表
     */
    private List<AppUsage> buildAppUsageList(Map<Integer, TrafficAmount> mobile,
            Map<Integer, TrafficAmount> wifi) {
        PackageManager packageManager = appContext.getPackageManager();
        Drawable placeholderIcon = appContext.getDrawable(R.drawable.ic_app_placeholder);
        Set<Integer> uids = new HashSet<>();
        uids.addAll(mobile.keySet());
        uids.addAll(wifi.keySet());

        List<AppUsage> result = new ArrayList<>();
        int systemUidCount = 0;
        long systemMobileRxBytes = 0L;
        long systemMobileTxBytes = 0L;
        long systemWifiRxBytes = 0L;
        long systemWifiTxBytes = 0L;

        for (int uid : uids) {
            TrafficAmount mobileUsage = mobile.get(uid);
            TrafficAmount wifiUsage = wifi.get(uid);
            long mobileRxBytes = mobileUsage == null ? 0L : mobileUsage.rxBytes;
            long mobileTxBytes = mobileUsage == null ? 0L : mobileUsage.txBytes;
            long wifiRxBytes = wifiUsage == null ? 0L : wifiUsage.rxBytes;
            long wifiTxBytes = wifiUsage == null ? 0L : wifiUsage.txBytes;
            if (mobileRxBytes + mobileTxBytes + wifiRxBytes + wifiTxBytes == 0L) {
                continue;
            }

            String[] packageNames = queryPackageNames(packageManager, uid);
            String primaryPackage = null;
            ApplicationInfo applicationInfo = null;
            if (packageNames != null) {
                // 共享 UID 下逐个尝试，取第一个可读取的包；受包可见性限制的包会被跳过
                for (String packageName : packageNames) {
                    applicationInfo = queryApplicationInfo(packageManager, packageName);
                    if (applicationInfo != null) {
                        primaryPackage = packageName;
                        break;
                    }
                }
            }
            if (applicationInfo == null || primaryPackage == null) {
                systemUidCount++;
                systemMobileRxBytes += mobileRxBytes;
                systemMobileTxBytes += mobileTxBytes;
                systemWifiRxBytes += wifiRxBytes;
                systemWifiTxBytes += wifiTxBytes;
                continue;
            }

            String label = primaryPackage;
            Drawable icon = placeholderIcon;
            try {
                CharSequence loadedLabel = applicationInfo.loadLabel(packageManager);
                if (loadedLabel != null && !loadedLabel.toString().trim().isEmpty()) {
                    label = loadedLabel.toString().trim();
                }
                Drawable loadedIcon = applicationInfo.loadIcon(packageManager);
                if (loadedIcon != null) {
                    icon = loadedIcon;
                }
            } catch (RuntimeException e) {
                // 应用信息异常时保留包名和占位图标，不影响整页数据
            }

            String detail = packageNames.length > 1
                    ? appContext.getString(R.string.app_usage_shared_uid,
                            primaryPackage, packageNames.length - 1)
                    : primaryPackage;
            result.add(new AppUsage(label, detail, icon,
                    mobileRxBytes, mobileTxBytes, wifiRxBytes, wifiTxBytes));
        }

        if (systemUidCount > 0) {
            result.add(new AppUsage(
                    appContext.getString(R.string.app_usage_system_group),
                    appContext.getString(R.string.app_usage_system_group_detail, systemUidCount),
                    placeholderIcon,
                    systemMobileRxBytes, systemMobileTxBytes,
                    systemWifiRxBytes, systemWifiTxBytes));
        }
        return result;
    }

    /**
     * 查询 UID 对应的已安装包名
     *
     * @param packageManager 包管理器
     * @param uid 应用 UID
     * @return 包名数组；无匹配时返回 null
     */
    private String[] queryPackageNames(PackageManager packageManager, int uid) {
        try {
            return packageManager.getPackagesForUid(uid);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 读取单个包的应用信息
     *
     * @param packageManager 包管理器
     * @param packageName 包名
     * @return 应用信息；包不存在或受包可见性限制时返回 null
     */
    private ApplicationInfo queryApplicationInfo(
            PackageManager packageManager, String packageName) {
        try {
            return packageManager.getApplicationInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }

    /**
     * 计算本次采集起点
     *
     * 有本期明细时从最近一次明细继续；本期尚未采集时从周期起点开始。
     *
     * @param lastRecordTime 该网络类型上一条明细时间，没有记录时为 0
     * @param periodStart 本期统计起点，Unix 毫秒时间戳
     * @return 采集起点，Unix 毫秒时间戳
     */
    private long getQueryStartTime(long lastRecordTime, long periodStart) {
        if (lastRecordTime > 0L && lastRecordTime >= periodStart) {
            return lastRecordTime;
        }
        return periodStart;
    }

    /**
     * 切回主线程结束采集并通知当前界面
     *
     * @param result 结果类型
     * @param reason 失败原因
     */
    private void postResult(int result, String reason) {
        mainHandler.post(() -> {
            Listener target;
            synchronized (stateLock) {
                collecting = false;
                target = listener;
                if (target == null && result != RESULT_SUCCESS) {
                    pendingResult = new PendingResult(result, reason);
                }
            }
            if (target != null) {
                deliver(target, result, reason);
            }
        });
    }

    /**
     * 在主线程把结果分发给界面
     *
     * @param target 界面回调
     * @param result 结果类型
     * @param reason 失败原因
     */
    private void deliver(Listener target, int result, String reason) {
        if (result == RESULT_SUCCESS) {
            target.onCollectSuccess();
        } else if (result == RESULT_PERMISSION_REVOKED) {
            target.onCollectPermissionRevoked();
        } else {
            target.onCollectFailure(reason == null
                    ? appContext.getString(R.string.collect_failed_default) : reason);
        }
    }

    /**
     * 等待新界面回放的失败结果
     */
    private static final class PendingResult {
        /** 结果类型 */
        final int result;

        /** 失败原因 */
        final String reason;

        /**
         * @param result 结果类型
         * @param reason 失败原因
         */
        PendingResult(int result, String reason) {
            this.result = result;
            this.reason = reason;
        }
    }

    /**
     * 可展示给用户的采集异常
     */
    private static final class CollectException extends RuntimeException {
        /**
         * @param message 用户可读的失败原因
         */
        CollectException(String message) {
            super(message);
        }
    }

    /**
     * 一次流量查询结果
     */
    private static final class TrafficAmount {
        /** 下载字节数 */
        final long rxBytes;

        /** 上传字节数 */
        final long txBytes;

        /**
         * @param rxBytes 下载字节数
         * @param txBytes 上传字节数
         */
        TrafficAmount(long rxBytes, long txBytes) {
            this.rxBytes = rxBytes;
            this.txBytes = txBytes;
        }
    }
}
