package com.assistente.launcher

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.ToneGenerator
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.KeyEvent
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

class AssistenteService : Service() {

    fun interface Ouvinte {
        fun atualizar()
    }

    companion object {
        @Volatile var instance: AssistenteService? = null
        @Volatile var ouvinte: Ouvinte? = null
        const val ACAO_FALAR = "com.assistente.launcher.FALAR"
        const val ACAO_PARAR = "com.assistente.launcher.PARAR"
        const val CANAL = "assistente"
    }

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("cfg", Context.MODE_PRIVATE) }

    // estado visível pela app
    var estadoTxt = "A iniciar…"
        private set
    var rostoTxt = "🙂"
        private set
    var pronto = false
        private set
    var ocupado = false
        private set
    var modoConversa = false
        private set

    private var chatFixo = ""
    private var chatParcial = ""
    fun chatCompleto() = chatFixo + chatParcial

    private var iniciado = false
    private var nomeIA = "Aria"
    private var modeloAtual: Modelo = RAPIDO
    private var llm: LlmInference? = null
    private val history = mutableListOf<Pair<String, String>>()

    @Volatile private var sessao: LlmInferenceSession? = null
    @Volatile private var ctxEst = 0
    private var liberado = false

    // geração em streaming
    private var geracaoId = 0
    private val respAcum = StringBuilder()
    private var falaIdx = 0
    private var falasPend = 0
    private var uttCount = 0
    private var geracaoFim = true
    private var vozAtiva = false
    private var nPartes = 0
    private var t0 = 0L
    private var tPrimeiro = 0L
    private var ultimaParte = 0L
    private var statsTxt = ""

    // voz
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsPronto = false
    private var ouvindo = false
    private var silencios = 0
    private val idiomas = listOf("pt-BR", "pt-PT", "pt")
    private var idiomaIdx = 0
    private var onDevice = true

    private var sessaoMidia: MediaSession? = null
    private var wl: PowerManager.WakeLock? = null

    private fun pronta() = "Pronta (offline) ✅$statsTxt"
    private fun estTokens(s: String) = s.length / 3 + 4

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACAO_PARAR) {
            prefs.edit().putBoolean("parado", true).apply()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        criarCanal()
        startForeground(
            2,
            montarNotificacao(estadoTxt.lineSequence().first()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
        instance = this
        if (!iniciado) {
            iniciado = true
            lerConfig()
            iniciarTts()
            iniciarMidia()
            carregarModelo()
        }
        if (intent?.action == ACAO_FALAR && pronto) botaoAuricular()
        ouvinte?.atualizar()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        iniciado = false
        instance = null
        pronto = false
        ui.removeCallbacksAndMessages(null)
        try { recognizer?.destroy() } catch (_: Throwable) {}
        try { tts?.shutdown() } catch (_: Throwable) {}
        resetSessao()
        try { llm?.close() } catch (_: Throwable) {}
        try { sessaoMidia?.release() } catch (_: Throwable) {}
        wl?.let { if (it.isHeld) it.release() }
        ouvinte?.atualizar()
        super.onDestroy()
    }

    // ---------- Notificação ----------
    private fun criarCanal() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CANAL, "Assistente", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun montarNotificacao(txt: String): Notification {
        val abrir = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val falar = PendingIntent.getService(
            this, 1,
            Intent(this, AssistenteService::class.java).setAction(ACAO_FALAR),
            PendingIntent.FLAG_IMMUTABLE
        )
        val parar = PendingIntent.getService(
            this, 2,
            Intent(this, AssistenteService::class.java).setAction(ACAO_PARAR),
            PendingIntent.FLAG_IMMUTABLE
        )
        val icone = Icon.createWithResource(this, android.R.drawable.ic_btn_speak_now)
        return Notification.Builder(this, CANAL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("$nomeIA ativa")
            .setContentText(txt)
            .setContentIntent(abrir)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(icone, "🎤 Falar", falar).build())
            .addAction(Notification.Action.Builder(icone, "⏹ Parar", parar).build())
            .build()
    }

    private fun atualizarNotificacao(txt: String) {
        if (!iniciado) return
        try {
            getSystemService(NotificationManager::class.java).notify(2, montarNotificacao(txt))
        } catch (_: Throwable) {
        }
    }

    private fun estado(t: String, notif: Boolean = true) {
        if (!iniciado) return
        estadoTxt = t
        if (notif) atualizarNotificacao(t.lineSequence().first())
        ouvinte?.atualizar()
    }

    private fun rosto(r: String) {
        rostoTxt = r
        ouvinte?.atualizar()
    }

    // ---------- Configuração e modelo ----------
    private fun lerConfig() {
        nomeIA = prefs.getString("nome", "Aria") ?: "Aria"
        modeloAtual = if (prefs.getString("modelo", "rapido") == "melhor") MELHOR else RAPIDO
        idiomaIdx = prefs.getInt("stt_idioma", 0).coerceIn(0, idiomas.size - 1)
        onDevice = prefs.getBoolean("stt_ondevice", true)
    }

    private fun carregarModelo() {
        val f = File(filesDir, modeloAtual.arquivo)
        if (!f.exists()) {
            estado("Modelo não encontrado. Abre a app para descarregar.")
            return
        }
        estado("A acordar $nomeIA…")
        thread {
            try {
                val opts = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(f.absolutePath)
                    .setMaxTokens(1024)
                    .setPreferredBackend(LlmInference.Backend.CPU)
                    .build()
                val m = LlmInference.createFromOptions(this@AssistenteService, opts)
                ui.post {
                    if (!iniciado) {
                        try { m.close() } catch (_: Throwable) {}
                        return@post
                    }
                    llm = m
                    aquecer(m)
                }
            } catch (e: Throwable) {
                ui.post { estado("Erro ao carregar: ${e.message}") }
            }
        }
    }

    private fun aquecer(m: LlmInference) {
        liberado = false
        estado("A afinar $nomeIA para este telemóvel…")
        ui.postDelayed({ if (!liberado) fimAquecer(null, 0.0) }, 60_000)
        thread {
            try {
                val s = novaSessao(m)
                s.addQueryChunk("<|im_start|>user\nConta de 1 a 10.<|im_end|>\n<|im_start|>assistant\n")
                var n = 0
                var tp = 0L
                s.generateResponseAsync { parte, done ->
                    if (parte != null && parte.isNotEmpty()) {
                        n++
                        if (n == 1) tp = System.currentTimeMillis()
                    }
                    if (done) {
                        val seg = maxOf((System.currentTimeMillis() - tp) / 1000.0, 0.1)
                        val tps = if (n >= 3) n / seg else 0.0
                        ui.post { fimAquecer(s, tps) }
                    }
                }
            } catch (e: Throwable) {
                ui.post { fimAquecer(null, 0.0) }
            }
        }
    }

    private fun fecharLento(s: LlmInferenceSession?) {
        if (s != null) {
            ui.postDelayed({ try { s.close() } catch (_: Throwable) {} }, 500)
        }
    }

    private fun fimAquecer(s: LlmInferenceSession?, tps: Double) {
        fecharLento(s)
        if (!iniciado || liberado) return
        liberado = true
        if (tps > 0.0) {
            prefs.edit().putFloat("tps_${modeloAtual.id}", tps.toFloat()).apply()
            statsTxt = String.format(Locale.US, "\nVelocidade medida: %.1f tokens/s", tps)
        }
        pronto = true
        estado(pronta())
    }

    // ---------- Sessão com memória ----------
    private fun novaSessao(m: LlmInference): LlmInferenceSession {
        val so = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(40)
            .setTemperature(0.4f)
            .build()
        return LlmInferenceSession.createFromOptions(m, so)
    }

    private fun resetSessao() {
        val s = sessao
        sessao = null
        ctxEst = 0
        try { s?.close() } catch (_: Throwable) {}
    }

    private fun promptNovo(msg: String): String {
        val sb = StringBuilder()
        sb.append("<|im_start|>system\nTu és $nomeIA, amigo e assistente do utilizador. ")
        sb.append("Responde em português de Portugal, em frases curtas e corretas, sem emojis.<|im_end|>\n")
        history.takeLast(4).forEach { (r, t) ->
            sb.append("<|im_start|>$r\n${t.take(200)}<|im_end|>\n")
        }
        sb.append("<|im_start|>user\n$msg<|im_end|>\n<|im_start|>assistant\n")
        return sb.toString()
    }

    // ---------- API usada pela app ----------
    fun enviarTexto(txt: String) {
        if (pronto && !ocupado) processar(txt, false)
    }

    fun tocarMic() {
        if (!pronto) return
        if (ouvindo) {
            recognizer?.stopListening()
        } else {
            silencios = 0
            ouvir()
        }
    }

    fun alternarConversa() {
        if (!pronto) {
            estado("Espera: a IA ainda está a acordar.")
            return
        }
        if (modoConversa) {
            pararVoz()
            estado(pronta())
        } else {
            modoConversa = true
            silencios = 0
            ouvir()
        }
        ouvinte?.atualizar()
    }

    fun pararVoz() {
        modoConversa = false
        ouvindo = false
        vozAtiva = false
        falasPend = 0
        try { recognizer?.cancel() } catch (_: Throwable) {}
        try { tts?.stop() } catch (_: Throwable) {}
        rosto("🙂")
    }

    private fun botaoAuricular() {
        if (!pronto) return
        if (tts?.isSpeaking == true) {
            pararVoz()
            estado(pronta())
            return
        }
        if (ouvindo) {
            recognizer?.stopListening()
            return
        }
        silencios = 0
        ouvir()
    }

    // ---------- Conversa com a IA (streaming) ----------
    private fun manterAcordado() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val w = wl ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "assistente:voz").also {
            it.setReferenceCounted(false)
            wl = it
        }
        w.acquire(2 * 60 * 1000L)
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!ocupado) return
            if (System.currentTimeMillis() - ultimaParte > 90_000L) {
                geracaoId++
                terminarComErro("Sem resposta (tempo esgotado). Tenta de novo.")
            } else {
                ui.postDelayed(this, 10_000)
            }
        }
    }

    private fun processar(txt: String, voz: Boolean) {
        val m = llm ?: return
        if (ocupado) return
        ocupado = true
        manterAcordado()
        if (chatFixo.length > 20000) chatFixo = chatFixo.takeLast(10000)
        chatFixo += "Tu: $txt\n\n"
        chatParcial = ""
        respAcum.setLength(0)
        falaIdx = 0
        falasPend = 0
        geracaoFim = false
        vozAtiva = voz
        nPartes = 0
        tPrimeiro = 0L
        geracaoId++
        val id = geracaoId
        rosto("🤔")
        estado("$nomeIA está a pensar…")
        val msg = if (voz) "$txt\n(Responde em no máximo duas frases curtas.)" else txt
        val nomeNaHora = nomeIA
        t0 = System.currentTimeMillis()
        ultimaParte = t0
        ui.removeCallbacks(watchdog)
        ui.postDelayed(watchdog, 10_000)
        thread {
            try {
                val atual = sessao
                val sess: LlmInferenceSession
                val chunk: String
                if (atual == null || ctxEst + estTokens(msg) > 600) {
                    resetSessao()
                    sess = novaSessao(m)
                    sessao = sess
                    chunk = promptNovo(msg)
                    ctxEst = estTokens(chunk)
                } else {
                    sess = atual
                    chunk = "\n<|im_start|>user\n$msg<|im_end|>\n<|im_start|>assistant\n"
                    ctxEst += estTokens(chunk)
                }
                sess.addQueryChunk(chunk)
                sess.generateResponseAsync { parte, done ->
                    ui.post { receberParte(id, parte ?: "", done, nomeNaHora, txt) }
                }
            } catch (e: Throwable) {
                ui.post {
                    if (id == geracaoId) terminarComErro("Erro: ${e.message}")
                }
            }
        }
    }

    private fun receberParte(id: Int, parte: String, done: Boolean, nome: String, pergunta: String) {
        if (id != geracaoId || !ocupado) return

        ultimaParte = System.currentTimeMillis()
        if (parte.isNotEmpty()) {
            nPartes++
            if (nPartes == 1) {
                tPrimeiro = ultimaParte
                if (!vozAtiva) estado("$nome está a escrever…", false)
            }
            respAcum.append(parte)
        }
        val limpo = respAcum.toString().replace("<|im_end|>", "").trimStart()

        if (!done) {
            chatParcial = "$nome: $limpo"
            ouvinte?.atualizar()
            if (vozAtiva) falarFrases(limpo, false)
            return
        }

        geracaoFim = true
        val resp = limpo.trim()
        history.add("user" to pergunta)
        history.add("assistant" to resp)
        ctxEst += estTokens(resp) + 5
        chatFixo += "$nome: $resp\n\n"
        chatParcial = ""
        ocupado = false

        statsTxt = if (nPartes >= 2) {
            val seg = (System.currentTimeMillis() - tPrimeiro) / 1000.0
            val primeira = (tPrimeiro - t0) / 1000.0
            String.format(
                Locale.US, "\n1ª palavra: %.1f s · %.1f tokens/s",
                primeira, nPartes / maxOf(seg, 0.1)
            )
        } else ""

        if (vozAtiva) {
            falarFrases(limpo, true)
            if (!ttsPronto) estado("Voz em português não disponível no telemóvel.")
            if (falasPend == 0) depoisDeFalar()
        } else {
            rosto("🙂")
            estado(pronta())
        }
    }

    private fun terminarComErro(msg: String) {
        resetSessao()
        ocupado = false
        geracaoFim = true
        vozAtiva = false
        falasPend = 0
        chatFixo += "$nomeIA: $msg\n\n"
        chatParcial = ""
        rosto("🙂")
        estado(pronta())
    }

    // ---------- Voz: falar (frase a frase) ----------
    private fun iniciarTts() {
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.let { t ->
                    var r = t.setLanguage(Locale("pt", "PT"))
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                        r = t.setLanguage(Locale("pt", "BR"))
                    }
                    ttsPronto = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                    t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            ui.post { falaTerminou() }
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            ui.post { falaTerminou() }
                        }
                    })
                }
            }
        }
    }

    private fun falarFrases(texto: String, fim: Boolean) {
        if (tts == null || !ttsPronto || !vozAtiva) return
        if (falaIdx >= texto.length) return
        val pend = texto.substring(falaIdx)
        if (fim) {
            falaIdx = texto.length
            enfileirar(pend)
            return
        }
        val idx = pend.indexOfLast { it == '.' || it == '!' || it == '?' || it == '\n' }
        if (idx >= 0) {
            falaIdx += idx + 1
            enfileirar(pend.substring(0, idx + 1))
        }
    }

    private fun enfileirar(frase: String) {
        val limpo = frase.replace(Regex("[^\\p{L}\\p{N}\\p{P}\\p{Z}]"), " ").trim()
        if (limpo.isEmpty()) return
        falasPend++
        rosto("😄")
        estado("$nomeIA está a falar…", false)
        uttCount++
        tts?.speak(limpo, TextToSpeech.QUEUE_ADD, null, "resp$uttCount")
    }

    private fun falaTerminou() {
        if (falasPend > 0) falasPend--
        if (falasPend == 0 && geracaoFim) depoisDeFalar()
    }

    private fun depoisDeFalar() {
        rosto("🙂")
        if (!ocupado && !estadoTxt.startsWith("Voz")) estado(pronta())
        if (modoConversa && !ocupado) ouvir()
    }

    // ---------- Voz: ouvir ----------
    private fun bipe() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            ui.postDelayed({ try { tg.release() } catch (_: Throwable) {} }, 500)
        } catch (_: Throwable) {
        }
    }

    private fun criarReconhecedor(): SpeechRecognizer? {
        val r = if (onDevice && Build.VERSION.SDK_INT >= 33 &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } else if (SpeechRecognizer.isRecognitionAvailable(this)) {
            onDevice = false
            SpeechRecognizer.createSpeechRecognizer(this)
        } else {
            return null
        }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                val p = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (p.isNotEmpty()) estado("A ouvir: $p", false)
            }

            override fun onResults(results: Bundle?) {
                ouvindo = false
                val txt = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                if (txt.isNotEmpty()) {
                    silencios = 0
                    prefs.edit()
                        .putInt("stt_idioma", idiomaIdx)
                        .putBoolean("stt_ondevice", onDevice)
                        .apply()
                    processar(txt, true)
                } else {
                    onError(SpeechRecognizer.ERROR_NO_MATCH)
                }
            }

            override fun onError(error: Int) {
                ouvindo = false
                rosto("🙂")
                when (error) {
                    SpeechRecognizer.ERROR_CLIENT -> {}
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_NO_MATCH -> {
                        silencios++
                        if (modoConversa && silencios < 3) {
                            estado("Não ouvi nada, a tentar de novo…", false)
                            ui.postDelayed({ if (modoConversa && !ouvindo && !ocupado) ouvir() }, 800)
                        } else {
                            pararVoz()
                            estado("Não te ouvi. Toca em 🎤 para tentar de novo.")
                        }
                    }
                    12, 13 -> {
                        if (idiomaIdx < idiomas.size - 1) {
                            idiomaIdx++
                            ui.post { ouvir() }
                        } else if (onDevice && Build.VERSION.SDK_INT >= 33) {
                            onDevice = false
                            idiomaIdx = 0
                            ui.post {
                                try { recognizer?.destroy() } catch (_: Throwable) {}
                                recognizer = null
                                ouvir()
                            }
                        } else {
                            idiomaIdx = 0
                            onDevice = true
                            pararVoz()
                            estado("O Android não tem reconhecimento de voz para português (erro $error). Verifica o pacote de reconhecimento.")
                        }
                    }
                    else -> {
                        pararVoz()
                        estado("Erro de voz (código $error, ${idiomas[idiomaIdx]}).")
                    }
                }
            }
        })
        return r
    }

    private fun ouvir() {
        if (ouvindo || ocupado || !pronto) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pararVoz()
            estado("Falta a permissão do microfone. Abre a app e autoriza.")
            return
        }
        try { tts?.stop() } catch (_: Throwable) {}
        manterAcordado()
        val r = recognizer ?: criarReconhecedor()
        if (r == null) {
            pararVoz()
            estado("Reconhecimento de voz indisponível neste telemóvel.")
            return
        }
        recognizer = r
        val lang = idiomas[idiomaIdx]
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        ouvindo = true
        rosto("👂")
        estado("A ouvir… ($lang${if (onDevice) "" else ", modo normal"})", false)
        bipe()
        ui.postDelayed({
            if (ouvindo) {
                try { r.startListening(i) } catch (e: Throwable) {
                    ouvindo = false
                    estado("Erro ao ouvir: ${e.message}")
                }
            }
        }, 300)
    }

    // ---------- Botão do auricular ----------
    private fun iniciarMidia() {
        try {
            val ms = MediaSession(this, "assistente")
            ms.setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val ev: KeyEvent? = if (Build.VERSION.SDK_INT >= 33) {
                        mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                    }
                    if (ev != null) {
                        when (ev.keyCode) {
                            KeyEvent.KEYCODE_HEADSETHOOK,
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                            KeyEvent.KEYCODE_MEDIA_PLAY,
                            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                                if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) {
                                    ui.post { botaoAuricular() }
                                }
                                return true
                            }
                        }
                    }
                    return super.onMediaButtonEvent(mediaButtonIntent)
                }
            })
            ms.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                            PlaybackState.ACTION_PLAY_PAUSE
                    )
                    .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                    .build()
            )
            ms.isActive = true
            sessaoMidia = ms
        } catch (_: Throwable) {
        }
    }
}
