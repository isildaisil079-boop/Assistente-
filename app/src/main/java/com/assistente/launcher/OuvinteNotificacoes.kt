package com.assistente.launcher

import android.service.notification.NotificationListenerService

// Só serve para o Android nos deixar controlar a música (sessões de média).
// Não lê nem guarda as tuas notificações.
class OuvinteNotificacoes : NotificationListenerService()
