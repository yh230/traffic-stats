package com.yh.trafficstats;

/**
 * 一条整机流量采集记录
 *
 * @author yh
 * @date 2026-10-23
 */
public class TrafficRecord {

    /** 采集时间，Unix 毫秒时间戳 */
    private final long recordTime;

    /** 网络类型，取值为 {@link TrafficDbHelper#NETWORK_TYPE_MOBILE} 或 {@link TrafficDbHelper#NETWORK_TYPE_WIFI} */
    private final String networkType;

    /** 下载字节数 */
    private final long rxBytes;

    /** 上传字节数 */
    private final long txBytes;

    /**
     * 创建一条流量记录
     *
     * @param recordTime 采集时间，Unix 毫秒时间戳
     * @param networkType 网络类型
     * @param rxBytes 下载字节数
     * @param txBytes 上传字节数
     */
    public TrafficRecord(long recordTime, String networkType, long rxBytes, long txBytes) {
        this.recordTime = recordTime;
        this.networkType = networkType;
        this.rxBytes = rxBytes;
        this.txBytes = txBytes;
    }

    public long getRecordTime() {
        return recordTime;
    }

    public String getNetworkType() {
        return networkType;
    }

    public long getRxBytes() {
        return rxBytes;
    }

    public long getTxBytes() {
        return txBytes;
    }
}
