package com.twitchnotify.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

public class TwitchMessagingService extends FirebaseMessagingService {

    private static final String CHANNEL_ID = "stream_alerts";

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Stream Alerts",
                NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Notifications when followed streamers go live");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override
    public void onMessageReceived(RemoteMessage message) {
        String title = message.getData().containsKey("title")
            ? message.getData().get("title") : "Stream is live!";
        String body = message.getData().containsKey("body")
            ? message.getData().get("body") : "";
        String streamerLogin = message.getData().get("streamerLogin");

        Intent intent;
        if (streamerLogin != null) {
            intent = new Intent(Intent.ACTION_VIEW, Uri.parse("twitch://stream/" + streamerLogin));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        } else {
            intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        }

        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String streamerId = message.getData().containsKey("streamerId")
            ? message.getData().get("streamerId") : "0";

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true);

        getSystemService(NotificationManager.class)
            .notify(streamerId.hashCode(), builder.build());
    }
}
