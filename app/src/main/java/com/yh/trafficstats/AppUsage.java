package com.yh.trafficstats;

import android.graphics.drawable.Drawable;

/**
 * 单个应用或系统 UID 的本期流量
 *
 * 数据由系统按 UID 聚合，同一 UID 下的多个应用会合并展示；本期累计只查询系统当前统计，
 * 不会写入本地数据库。
 *
 * @author yh
 * @date 2026-10-26
 */
public final class AppUsage {

    /** 应用或系统项名称 */
    private final String label;

    /** 应用包名或系统 UID 说明 */
    private final String detail;

    /** 列表图标 */
    private final Drawable icon;

    /** 本期移动网络下载字节数 */
    private final long mobileRxBytes;

    /** 本期移动网络上传字节数 */
    private final long mobileTxBytes;

    /** 本期 WiFi 下载字节数 */
    private final long wifiRxBytes;

    /** 本期 WiFi 上传字节数 */
    private final long wifiTxBytes;

    /**
     * 创建一条应用流量记录
     *
     * @param label 应用或系统项名称
     * @param detail 应用包名或系统 UID 说明
     * @param icon 列表图标
     * @param mobileRxBytes 本期移动网络下载字节数
     * @param mobileTxBytes 本期移动网络上传字节数
     * @param wifiRxBytes 本期 WiFi 下载字节数
     * @param wifiTxBytes 本期 WiFi 上传字节数
     */
    AppUsage(String label, String detail, Drawable icon,
            long mobileRxBytes, long mobileTxBytes,
            long wifiRxBytes, long wifiTxBytes) {
        this.label = label;
        this.detail = detail;
        this.icon = icon;
        this.mobileRxBytes = mobileRxBytes;
        this.mobileTxBytes = mobileTxBytes;
        this.wifiRxBytes = wifiRxBytes;
        this.wifiTxBytes = wifiTxBytes;
    }

    public String getLabel() {
        return label;
    }

    public String getDetail() {
        return detail;
    }

    public Drawable getIcon() {
        return icon;
    }

    /**
     * 读取指定网络类型的下载字节数
     *
     * @param mobile 是否移动网络
     * @return 下载字节数
     */
    public long getRxBytes(boolean mobile) {
        return mobile ? mobileRxBytes : wifiRxBytes;
    }

    /**
     * 读取指定网络类型的上传字节数
     *
     * @param mobile 是否移动网络
     * @return 上传字节数
     */
    public long getTxBytes(boolean mobile) {
        return mobile ? mobileTxBytes : wifiTxBytes;
    }

    /**
     * 读取指定网络类型的下载与上传总量
     *
     * @param mobile 是否移动网络
     * @return 总字节数
     */
    public long getTotalBytes(boolean mobile) {
        return getRxBytes(mobile) + getTxBytes(mobile);
    }
}
