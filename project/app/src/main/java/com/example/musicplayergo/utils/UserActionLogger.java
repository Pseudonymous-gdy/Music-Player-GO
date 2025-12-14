package com.example.musicplayergo.utils;

import android.annotation.SuppressLint;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

public class UserActionLogger {

    private static final String TAG = "ActionLogger";
    private static final FirebaseFirestore db = FirebaseFirestore.getInstance();
    private static String cachedUserId;

    /**
     * 核心方法：自动上传一条行为日志
     *
     * @param userId     当前登录的用户ID
     * @param actionType 动作类型 (比如 "click_play", "skip_song", "view_page")
     * @param details    具体的细节参数 (比如歌曲ID，停留时长)
     */
    public static void logAction(String userId, String actionType, Map<String, Object> details) {

        // 1. 准备要上传的数据包
        Map<String, Object> logData = new HashMap<>();

        // 基础信息 (自动补全)
        logData.put("user_id", userId);
        logData.put("action_type", actionType);
        logData.put("timestamp", Timestamp.now()); // 关键：自动打上服务器时间戳
        logData.put("device_os", "Android");

        // 合并细节参数 (如果有的话)
        if (details != null) {
            logData.putAll(details);
        }

        // 2. 这里的 "user_logs" 就是你在控制台看到的【集合名称】
        // 使用 .add() 方法，Firestore 会自动生成一个唯一的文档 ID (乱码)
        db.collection("user_logs")
            .add(logData)
            .addOnSuccessListener(documentReference -> {
                Log.d(TAG, "✅ 自动上传成功! ID: " + documentReference.getId());
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "❌ 上传失败", e);
            });
    }

    /**
     * 自动获取设备的唯一 ID，避免重复传入 userId
     */
    public static void logAction(Context context, String actionType, Map<String, Object> details) {
        logAction(getUserId(context), actionType, details);
    }

    @SuppressLint("HardwareIds")
    public static String getUserId(Context context) {
        if (cachedUserId != null) {
            return cachedUserId;
        }

        String androidId = "unknown_user";
        try {
            String secureId = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ANDROID_ID
            );
            if (secureId != null && !secureId.isEmpty()) {
                androidId = secureId;
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to read ANDROID_ID", e);
        }

        cachedUserId = androidId;
        return cachedUserId;
    }
}
