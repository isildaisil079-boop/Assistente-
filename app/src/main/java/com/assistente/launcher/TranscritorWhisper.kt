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
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.sqrt

class TranscritorWhisper(private val ctx: Context) {

    companion object {
        // Informação técnica da última gravação (mostrada no estado)
        @Volatile var ultimaDiag = ""
    }

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
        val fios = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
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
                numThreads = fios,
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
                // Carrega o modelo em paralelo, enquanto gravas
                thread {
                    try { carregar() } catch (_: Throwable) {}
                }

                val fimFalaMs = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
                    .getInt("fim_fala_ms", 1200)

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

                val passo = sr / 20 // blocos de 50 ms
                val bloco = ShortArray(passo)
                val todos = ShortArray(sr * 20)
                var n = 0
                var piso = -1.0
                var limFala = 400.0
                var limSil = 250.0
                var falou = false
                var silMs = 0
                var falaMs = 0
                var tempoMs = 0
                var inicioFala = 0

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
                    tempoMs += 50

                    if (tempoMs <= 300) {
                        // calibração: o valor mais baixo é o ruído de fundo
                        piso = if (piso < 0) rms else minOf(piso, rms)
                        limFala = (piso * 3.0).coerceIn(400.0, 3000.0)
                        limSil = (piso * 2.0).coerceIn(250.0, 2000.0)
                        continue
                    }

                    val emFala = if (falou) rms > limSil else rms > limFala
                    if (emFala) {
                        if (!falou) {
                            falou = true
                            inicioFala = n - lidos
                            aoComecarFala()
                        }
                        falaMs += 50
                        silMs = 0
                    } else if (falou) {
                        silMs += 50
                    } else {
                        // ainda sem fala: o ruído de fundo adapta-se ao ambiente
                        piso = piso * 0.95 + rms * 0.05
                        limFala = (piso * 3.0).coerceIn(400.0, 3000.0)
                        limSil = (piso * 2.0).coerceIn(250.0, 2000.0)
                    }
                    if (falou && silMs >= fimFalaMs && falaMs >= 300) break
                    if (!falou && tempoMs >= 7000) break
                }

                try { ar.stop() } catch (_: Throwable) {}
                gravando = false
                if (cancelado()) return@thread
                if (!falou) {
                    ultimaDiag = String.format(
                        Locale.US, "Sem fala detetada · ruído %.0f · limiar %.0f", piso, limFala
                    )
                    aoFinal("", 0L, null)
                    return@thread
                }

                aoTranscrever()

                // Recorte: 0,4 s antes da fala e 0,5 s de silêncio no fim
                val ini = maxOf(0, inicioFala - sr * 4 / 10)
                val cortar = (silMs - 500).coerceAtLeast(0) * sr / 1000
                val fim = maxOf(ini + 1, n - cortar)

                // Amplifica se o áudio estiver baixo
                var pico = 0
                for (k in ini until fim) {
                    val a = abs(todos[k].toInt())
                    if (a > pico) pico = a
                }
                val picoF = pico / 32768f
                val ganho = if (picoF in 0.001f..0.6f) minOf(10f, 0.8f / picoF) else 1f
                val amostras = FloatArray(fim - ini) { (todos[ini + it] * ganho) / 32768f }

                ultimaDiag = String.format(
                    Locale.US, "áudio %.1f s · pico %d%% · ruído %.0f · ganho x%.1f",
                    (fim - ini) / 16000.0, (picoF * 100).toInt(), piso, ganho
                )

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
