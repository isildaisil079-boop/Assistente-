package com.assistente.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager

class AlarmeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> Acoes.rearmar(ctx)
            Acoes.ACAO_ALARME -> disparar(ctx, intent)
        }
    }

    private fun disparar(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra("id", 0)
        val texto = intent.getStringExtra("texto") ?: "Lembrete"
        val tipo = intent.getStringExtra("tipo") ?: "lembrete"
        Acoes.removerDisparado(ctx, id)

        val nm = ctx.getSystemService(NotificationManager::class.java)
        val canal = NotificationChannel(
            "alarmes", "Alarmes e lembretes", NotificationManager.IMPORTANCE_HIGH
        )
        canal.setSound(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        canal.enableVibration(true)
        nm.createNotificationChannel(canal)

        val abrir = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val titulo = when (tipo) {
            "alarme" -> "⏰ Alarme"
            "timer" -> "⏱ Temporizador"
            else -> "🔔 Lembrete"
        }
        val n = Notification.Builder(ctx, "alarmes")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setContentIntent(abrir)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setTimeoutAfter(60_000L)
            .build()
        if (tipo != "lembrete") n.flags = n.flags or Notification.FLAG_INSISTENT
        nm.notify(10000 + (id % 1000), n)

        val falar = when (tipo) {
            "alarme" -> null
            "timer" -> "O temporizador terminou."
            else -> "Lembrete: $texto"
        }
        if (falar != null) AssistenteService.instance?.anunciar(falar)
    }
}
