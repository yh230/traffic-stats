package com.yh.trafficstats;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 流量数据显示格式化工具
 *
 * @author yh
 * @date 2026-10-26
 */
public final class TrafficFormatter {

    private TrafficFormatter() {
    }

    /**
     * 将字节数转换为便于阅读的文本
     *
     * @param bytes 字节数
     * @return 带单位的流量文本
     */
    public static String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double kilobytes = bytes / 1024.0;
        if (kilobytes < 1024.0) {
            return String.format(Locale.CHINA, "%.1f KB", kilobytes);
        }
        double megabytes = kilobytes / 1024.0;
        if (megabytes < 1024.0) {
            return String.format(Locale.CHINA, "%.1f MB", megabytes);
        }
        return String.format(Locale.CHINA, "%.2f GB", megabytes / 1024.0);
    }

    /**
     * 把统计起点格式化为精确到分钟的本地时间
     *
     * @param timeMillis Unix 毫秒时间戳
     * @return 本地时区的时间文本
     */
    public static String formatPeriodTime(long timeMillis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                .format(new Date(timeMillis));
    }
}
