package com.assistente.launcher

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.sqrt

class TranscritorWhisper(private val ctx: Context) {

    // "base" ou "small"
    var variante: String = "base"
        set(v) {
            if (v != field) {
                field = v
                liberar()
            }
        }

    // código curto do idioma: pt, en, es, fr
    var idioma: String = "pt"
        set(v) {
            if (v != field) {
                field = v
                liberar()
            }
        }

    @Volatile private var rec: OfflineRecognizer? = null
    @Volatile private var terminarJaFlag = false
    @Volatile var gravando = false
        private set

    fun arquivos() = listOf(
        "$variante-encoder.int8.onnx",
        "$variante-decoder.int8.onnx",
        "$variante-tokens.txt"
    )

    fun totalMb() = if (variante == "small") 375 else 160

    private fun urlBase() =
        "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-$variante/resolve/main/"

    fun pasta() = File(ctx.filesDir, "whisper_$variante")

    fun instalado(): Boolean = arquivos().all {
        val f = File(pasta(), it)
        f.exists() && f.length() > 100_000L
    }

    // ---------- Download (com retoma) ----------
    fun baixarTudo(progresso: (Int, Int, Int) -> Unit) {
        pasta().mkdirs()
        val lista = arquivos()
        lista.forEachIndexed { i, nome ->
            val destino = File(pasta(), nome)
            if (!(destino.exists() && destino.length() > 100_000L)) {
                var falhas = 0
                while (true) {
                    try {
                        baixarUm(urlBase() + nome, destino) { pct -> progresso(i + 1, lista.size, pct) }
                        break
                    } catch (e: Exception) {
                        falhas++
                        if (falhas >= 8) throw e
                        try { Thread.sleep(4000) } catch (_: InterruptedException) {}
                    }
                }
            }
        }
    }

    private fun baixarUm(url: String, destino: File, pct: (Int) -> Unit) {
        val tmp = File(destino.path + ".tmp")
        val ja = if (tmp.exists()) tmp.length() else 0L
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 30000
        c.instanceFollowRedirects = true
        if (ja > 0) c.setRequestProperty("Range", "bytes=$ja-")
        val code = c.responseCode
        if (code == 416) {
            if (ja > 100_000L) {
                tmp.renameTo(destino)
                return
            }
            tmp.delete()
            throw Exception("a recomeçar")
        }
        if (code != 200 && code != 206) throw Exception("HTTP $code")
        val append = code == 206
        val inicio = if (append) ja else 0L
        val resto = c.contentLengthLong
        val total = if (resto > 0) inicio + resto else -1L
        var feito = inicio
        var ultimo = -1
        c.inputStream.use { inp ->
            FileOutputStream(tmp, append).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    feito += n
                    if (total > 0) {
                        val p = (feito * 100 / total).toInt()
                        if (p != ultimo) {
                            ultimo = p
                            pct(p)
                        }
                    }
                }
            }
        }
        if (total > 0 && tmp.length() < total) throw Exception("download incompleto")
        if (!tmp.renameTo(destino)) throw Exception("não consegui guardar o ficheiro")
    }

    // ---------- Modelo ----------
    @Synchronized
    private fun carregar(): OfflineRecognizer {
        rec?.let { return it }
        val d = pasta()
        val a = arquivos()
        val cfg = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = File(d, a[0]).absolutePath,
                    decoder = File(d, a[1]).absolutePath,
                    language = idioma,
                    task = "transcribe"
                ),
                tokens = File(d, a[2]).absolutePath,
                numThreads = 2,
                debug = false,
                provider = "cpu",
                modelType = "whisper"
            ),
            decodingMethod = "greedy_search"
        )
        val r = OfflineRecognizer(assetManager = null, config = cfg)
        rec = r
        return r
    }

    @Synchronized
    fun liberar() {
        if (gravando) return
        try { rec?.release() } catch (_: Throwable) {}
        rec = null
    }

    fun terminarJa() {
        terminarJaFlag = true
    }

    // ---------- Gravar até haver silêncio e transcrever ----------
    fun ouvir(
        aoComecarFala: () -> Unit,
        aoTranscrever: () -> Unit,
        cancelado: () -> Boolean,
        aoFinal: (String?, Long, String?) -> Unit
    ) {
        thread {
            var gravador: AudioRecord? = null
            try {
                val sr = 16000
                val minBuf = AudioRecord.getMinBufferSize(
                    sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuf <= 0) throw Exception("microfone indisponível")
                val ar = AudioRecord(
                    MediaRecorder.AudioSource.MIC, sr,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, sr * 2)
                )
                gravador = ar
                if (ar.state != AudioRecord.STATE_INITIALIZED) {
                    throw Exception("não consegui abrir o microfone")
                }
                terminarJaFlag = false
                gravando = true
                ar.startRecording()

                val bloco = ShortArray(sr / 10)
                val todos = ShortArray(sr * 15)
                var n = 0
                var calib = 0
                var ruido = 0.0
                var limiar = 600.0
                var falou = false
                var silencioMs = 0
                var falaMs = 0
                var tempoMs = 0

                while (!cancelado() && !terminarJaFlag) {
                    val lidos = ar.read(bloco, 0, bloco.size)
                    if (lidos < 0) throw Exception("erro de leitura do microfone")
                    if (lidos == 0) continue
                    if (n + lidos > todos.size) break
                    System.arraycopy(bloco, 0, todos, n, lidos)
                    n += lidos
                    var soma = 0.0
                    for (k in 0 until lidos) {
                        val v = bloco[k].toDouble()
                        soma += v * v
                    }
                    val rms = sqrt(soma / lidos)
                    tempoMs += 100
                    if (calib < 3) {
                        ruido += rms
                        calib++
                        if (calib == 3) limiar = (ruido / 3.0 * 3.0).coerceIn(450.0, 2500.0)
                        continue
                    }
                    if (rms > limiar) {
                        if (!falou) {
                            falou = true
                            aoComecarFala()
                        }
                        falaMs += 100
                        silencioMs = 0
                    } else if (falou) {
                        silencioMs += 100
                    }
                    if (falou && silencioMs >= 1100 && falaMs >= 300) break
                    if (!falou && tempoMs >= 7000) break
                }

                try { ar.stop() } catch (_: Throwable) {}
                gravando = false
                if (cancelado()) return@thread
                if (!falou) {
                    aoFinal("", 0L, null)
                    return@thread
                }

                aoTranscrever()
                val amostras = FloatArray(n) { todos[it] / 32768f }
                val t0 = System.currentTimeMillis()
                val r = carregar()
                val s = r.createStream()
                var texto = ""
                try {
                    s.acceptWaveform(amostras, sr)
                    r.decode(s)
                    texto = r.getResult(s).text
                } finally {
                    s.release()
                }
                if (cancelado()) return@thread
                var t = texto.trim()
                if (t.lowercase().contains("amara.org")) t = ""
                aoFinal(t, System.currentTimeMillis() - t0, null)
            } catch (e: Throwable) {
                if (!cancelado()) aoFinal(null, 0L, e.message ?: e.javaClass.simpleName)
            } finally {
                gravando = false
                try { gravador?.release() } catch (_: Throwable) {}
            }
        }
    }
}
