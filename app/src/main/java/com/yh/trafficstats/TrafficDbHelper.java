package com.yh.trafficstats;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地流量记录数据库帮助类
 *
 * 数据全部保存在应用私有目录的 SQLite 文件中，不涉及网络上传；
 * 卸载应用会同时删除数据库，应用被杀死不会丢失已保存的记录。
 *
 * @author yh
 * @date 2026-10-23
 */
public class TrafficDbHelper extends SQLiteOpenHelper {

    /** 数据库文件名 */
    private static final String DB_NAME = "traffic.db";

    /** 当前数据库版本 */
    private static final int DB_VERSION = 2;

    /** 流量记录表名 */
    private static final String TABLE_RECORD = "traffic_record";

    /** 本期统计周期表名，固定只有一行 */
    private static final String TABLE_PERIOD = "stats_period";

    private static final String COL_ID = "_id";

    /** 本期起点，Unix 毫秒时间戳 */
    private static final String COL_PERIOD_START = "period_start";

    /** 采集时间，Unix 毫秒时间戳 */
    private static final String COL_RECORD_TIME = "record_time";

    /** 网络类型，取值为 MOBILE 或 WIFI */
    private static final String COL_NETWORK_TYPE = "network_type";

    /** 下载字节数 */
    private static final String COL_RX_BYTES = "rx_bytes";

    /** 上传字节数 */
    private static final String COL_TX_BYTES = "tx_bytes";

    /** 移动网络类型值 */
    public static final String NETWORK_TYPE_MOBILE = "MOBILE";

    /** WiFi 网络类型值 */
    public static final String NETWORK_TYPE_WIFI = "WIFI";

    /**
     * 创建数据库帮助类
     *
     * @param context 应用上下文
     */
    public TrafficDbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_RECORD + " ("
                + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COL_RECORD_TIME + " INTEGER NOT NULL, "
                + COL_NETWORK_TYPE + " TEXT NOT NULL, "
                + COL_RX_BYTES + " INTEGER NOT NULL DEFAULT 0, "
                + COL_TX_BYTES + " INTEGER NOT NULL DEFAULT 0)");

        // 按网络类型查询最近一次采集时间时使用该索引
        db.execSQL("CREATE INDEX idx_traffic_record_type_time ON " + TABLE_RECORD
                + " (" + COL_NETWORK_TYPE + ", " + COL_RECORD_TIME + ")");

        db.execSQL("CREATE TABLE " + TABLE_PERIOD + " ("
                + COL_ID + " INTEGER PRIMARY KEY CHECK (" + COL_ID + " = 1), "
                + COL_PERIOD_START + " INTEGER NOT NULL)");
        insertPeriodRow(db, System.currentTimeMillis());
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE " + TABLE_PERIOD + " ("
                    + COL_ID + " INTEGER PRIMARY KEY CHECK (" + COL_ID + " = 1), "
                    + COL_PERIOD_START + " INTEGER NOT NULL)");

            long periodStart = System.currentTimeMillis();
            String sql = "SELECT MIN(" + COL_RECORD_TIME + ") FROM " + TABLE_RECORD;
            try (Cursor cursor = db.rawQuery(sql, null)) {
                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                    periodStart = cursor.getLong(0);
                }
            }
            insertPeriodRow(db, periodStart);
        }
    }

    /**
     * 插入本期起点行
     *
     * @param db 数据库
     * @param periodStart 本期起点，Unix 毫秒时间戳
     */
    private void insertPeriodRow(SQLiteDatabase db, long periodStart) {
        ContentValues values = new ContentValues();
        values.put(COL_ID, 1);
        values.put(COL_PERIOD_START, periodStart);
        db.insertOrThrow(TABLE_PERIOD, null, values);
    }

    /**
     * 读取本期统计起点；缺失时以当前时间初始化
     *
     * @return 本期起点，Unix 毫秒时间戳
     */
    public long getPeriodStartTime() {
        SQLiteDatabase db = getWritableDatabase();
        try (Cursor cursor = db.query(TABLE_PERIOD, new String[]{COL_PERIOD_START},
                COL_ID + " = 1", null, null, null, null)) {
            if (cursor.moveToFirst()) {
                return cursor.getLong(cursor.getColumnIndexOrThrow(COL_PERIOD_START));
            }
        }
        long now = System.currentTimeMillis();
        insertPeriodRow(db, now);
        return now;
    }

    /**
     * 重置本期统计：更新起点并清空采集明细
     *
     * 起点更新与明细删除在同一事务中完成，避免重置中途失败留下不一致状态。
     *
     * @param periodStart 新的本期起点，Unix 毫秒时间戳
     */
    public void resetPeriod(long periodStart) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put(COL_PERIOD_START, periodStart);
            int updated = db.update(TABLE_PERIOD, values, COL_ID + " = 1", null);
            if (updated == 0) {
                insertPeriodRow(db, periodStart);
            }
            db.delete(TABLE_RECORD, null, null);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * 在同一事务中插入移动网络和 WiFi 两条记录
     *
     * 两条记录要么都写入成功，要么都不写入，避免历史列表出现只有一条网络的残缺采集。
     *
     * @param recordTime 采集时间，Unix 毫秒时间戳
     * @param mobileRxBytes 移动网络下载字节数
     * @param mobileTxBytes 移动网络上传字节数
     * @param wifiRxBytes WiFi 下载字节数
     * @param wifiTxBytes WiFi 上传字节数
     */
    public void insertRecordPair(long recordTime,
            long mobileRxBytes, long mobileTxBytes,
            long wifiRxBytes, long wifiTxBytes) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            insertSingleRecord(db, recordTime, NETWORK_TYPE_MOBILE, mobileRxBytes, mobileTxBytes);
            insertSingleRecord(db, recordTime, NETWORK_TYPE_WIFI, wifiRxBytes, wifiTxBytes);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * 插入单条流量记录，调用方负责事务
     *
     * @param db 可写数据库
     * @param recordTime 采集时间，Unix 毫秒时间戳
     * @param networkType 网络类型
     * @param rxBytes 下载字节数
     * @param txBytes 上传字节数
     */
    private void insertSingleRecord(SQLiteDatabase db, long recordTime, String networkType,
            long rxBytes, long txBytes) {
        ContentValues values = new ContentValues();
        values.put(COL_RECORD_TIME, recordTime);
        values.put(COL_NETWORK_TYPE, networkType);
        values.put(COL_RX_BYTES, rxBytes);
        values.put(COL_TX_BYTES, txBytes);
        db.insertOrThrow(TABLE_RECORD, null, values);
    }

    /**
     * 查询全部流量记录，最新记录在前
     *
     * @return 按采集时间倒序排列的记录列表
     */
    public List<TrafficRecord> queryAllRecords() {
        List<TrafficRecord> result = new ArrayList<>();
        String orderBy = COL_RECORD_TIME + " DESC, " + COL_ID + " DESC";
        try (Cursor cursor = getReadableDatabase().query(
                TABLE_RECORD,
                new String[]{COL_RECORD_TIME, COL_NETWORK_TYPE, COL_RX_BYTES, COL_TX_BYTES},
                null,
                null,
                null,
                null,
                orderBy)) {
            while (cursor.moveToNext()) {
                result.add(new TrafficRecord(
                        cursor.getLong(cursor.getColumnIndexOrThrow(COL_RECORD_TIME)),
                        cursor.getString(cursor.getColumnIndexOrThrow(COL_NETWORK_TYPE)),
                        cursor.getLong(cursor.getColumnIndexOrThrow(COL_RX_BYTES)),
                        cursor.getLong(cursor.getColumnIndexOrThrow(COL_TX_BYTES))));
            }
        }
        return result;
    }

    /**
     * 查询指定网络类型最近一次采集时间
     *
     * @param networkType 网络类型，取值为 MOBILE 或 WIFI
     * @return 最近一次采集时间；没有记录时返回 0
     */
    public long getLastRecordTime(String networkType) {
        String sql = "SELECT MAX(" + COL_RECORD_TIME + ") FROM " + TABLE_RECORD
                + " WHERE " + COL_NETWORK_TYPE + " = ?";
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, new String[]{networkType})) {
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getLong(0);
            }
        }
        return 0L;
    }
}
