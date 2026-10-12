package com.assistente.launcher

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.MediaStore
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.view.KeyEvent
import java.util.Locale

// Chamadas, mensagens, música e volume, com confirmação antes de ações irreversíveis.
class AcoesTelefone(private val ctx: Context, private val svc: AssistenteService) {

    private class Contacto(val nome: String, val numero: String)
    private class Achado(val contacto: Contacto, val pontos: Int)
    private class Pendente(val acao: () -> String, val expira: Long)

    private val ui = Handler(Looper.getMainLooper())
    private var pendente: Pendente? = null
    @Volatile var aguardaResposta = false
    private var reqCode = 100

    private val LANCADO = 0
    private val NOTIFICACAO = 1
    private val SEM_APP = 2

    private fun norm(s: String) = Acoes.norm(s)

    private val reMsgMarcador = Regex(
        """^\s*(?:por favor[,\s]+)?(?:manda|mandar|envia|enviar|escreve|escrever)(?:-me|\s+me)?\s+(.+?)\s+(?:a\s+dizer(?:\s+que)?|dizendo(?:\s+que)?|a\s+avisar(?:\s+que)?|que\s+diga|a\s+perguntar(?:\s+se)?|com\s+o\s+texto)\s*[:,]?\s*(.+)$""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private val reMsgDoisPontos = Regex(
        """^\s*(?:por favor[,\s]+)?(?:manda|mandar|envia|enviar|escreve|escrever)(?:-me|\s+me)?\s+(.+?)\s*:\s*(.+)$""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private val reNomePlataforma = Regex("""(?i)\b(whats\s?app|watsap\w*|uatsap\w*|zap|wpp)\b""")
    private val reTemMsg = Regex("""(?i)\b(mensagem|mensagens|sms|texto|whats\s?app|watsap\w*|uatsap\w*|zap|wpp)\b""")

    private val reLigar = Regex(
        """^(?:por favor )?(?:faz uma chamada|fazer uma chamada|liga|ligar|telefona|telefonar|chama|chamar|contacta|contactar)(?: me)?(?: para| a| ao)?(?: o| a| os| as)? (.+)$"""
    )

    private val reMusica = Regex(
        """^(?:por favor )?(?:poe a tocar|poe me a tocar|quero ouvir|toca me|tocar|toca|poe me|poe|reproduzir|reproduz|ouvir)\b(?: a musica| musica| musicas| uns| umas| um| uma| a| o| os| as)? ?(.*)$"""
    )

    // ---------- Confirmação ----------
    fun consumirAguarda(): Boolean {
        val v = aguardaResposta
        aguardaResposta = false
        return v
    }

    fun resolverPendente(txt: String): String? {
        val p = pendente ?: return null
        pendente = null
        aguardaResposta = false
        if (System.currentTimeMillis() > p.expira) return null
        return when (simNao(txt)) {
            true -> try { p.acao() } catch (e: Throwable) { "Não consegui: ${e.message}" }
            false -> "Cancelado."
            null -> null
        }
    }

    private fun simNao(original: String): Boolean? {
        val ps = norm(original).split(" ").filter { it.isNotEmpty() }
        if (ps.isEmpty()) return null
        val nao = setOf("nao", "cancela", "cancelar", "esquece", "deixa", "negativo", "para", "pare", "no")
        val sim = setOf(
            "sim", "confirmo", "confirmar", "claro", "pode", "podes", "faz", "faca", "liga", "ligar",
            "envia", "enviar", "manda", "mandar", "ok", "okay", "exato", "certo", "avanca", "vai", "yes"
        )
        if (ps.any { it in nao }) return false
        if (ps.any { it in sim }) return true
        return null
    }

    private fun pedirConfirmacao(pergunta: String, voz: Boolean, acao: () -> String): String {
        pendente = Pendente(acao, System.currentTimeMillis() + 30_000L)
        aguardaResposta = voz
        return "$pergunta Diz sim ou não."
    }

    // ---------- Pedidos ----------
    private fun limparAlvo(g: String): String {
        var a = g
        a = a.replace(Regex("(?i)\\b(uma|um)\\b"), " ")
        a = a.replace(Regex("(?i)\\b(mensagens?|sms|textos?)\\b"), " ")
        a = a.replace(
            Regex("(?i)\\b(pelo|pela|no|na|por|via|em)\\s+(whats\\s?app|watsap\\w*|uatsap\\w*|zap|wpp)\\b"), " "
        )
        a = a.replace(reNomePlataforma, " ")
        a = a.replace(Regex("\\s+"), " ").trim()
        repeat(3) {
            a = a.replace(
                Regex("(?i)^(a|à|ao|aos|para|pro|pra|o|os|de|do|da|por|no|na|em|pelo|pela)\\s+"), ""
            )
        }
        return a.trim()
    }

    fun tentar(original: String, voz: Boolean): String? {
        val t = norm(original)
        if (t.isEmpty() || t.length > 220) return null

        if (t.length <= 40) {
            val m = midiaOuVolume(t)
            if (m != null) return m
        }

        val orig = original.trim()
        val mm = reMsgMarcador.find(orig) ?: reMsgDoisPontos.find(orig)
        if (mm != null && reTemMsg.containsMatchIn(mm.groupValues[1])) {
            val g1 = mm.groupValues[1]
            val whats = reNomePlataforma.containsMatchIn(g1)
            val alvo = limparAlvo(g1)
            var texto = mm.groupValues[2].trim().replace(Regex("(?i)^que\\s+"), "")
            texto = texto.replaceFirstChar { it.uppercase() }
            if (alvo.isNotEmpty() && texto.isNotEmpty()) {
                return prepararMensagem(alvo, texto, whats, voz)
            }
        }

        val ml = reLigar.find(t)
        if (ml != null) {
            val alvo = ml.groupValues[1].trim()
            val explicito = t.contains(" para ") || t.contains("chamada") ||
                Regex("^(?:por favor )?(?:telefon|contact)").containsMatchIn(t)
            return prepararLigacao(alvo, explicito, voz)
        }

        val mu = reMusica.find(t)
        if (mu != null) {
            var q = mu.groupValues[1].trim()
            q = q.replace(Regex("^(de|do|dos|da|das)\\s+"), "")
            var pacote: String? = null
            val app = Regex("\\s*(?:no|na|em)\\s+(spotify|youtube music|youtube|deezer)$").find(q)
            if (app != null) {
                pacote = when (app.groupValues[1]) {
                    "spotify" -> "com.spotify.music"
                    "deezer" -> "deezer.android.app"
                    else -> "com.google.android.apps.youtube.music"
                }
                q = q.removeRange(app.range).trim()
            }
            return tocarMusica(q, pacote)
        }
        return null
    }

    // ---------- Contactos ----------
    private fun temContactos() =
        ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun procurarContactos(consulta: String): List<Achado> {
        val q = norm(consulta).split(" ").filter { it.isNotEmpty() }
        if (q.isEmpty()) return emptyList()
        val qJunto = q.joinToString(" ")
        val mapa = LinkedHashMap<String, Pair<Contacto, Int>>()
        ctx.contentResolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE),
            null, null, null
        )?.use { c ->
            val iN = c.getColumnIndex(Phone.DISPLAY_NAME)
            val iP = c.getColumnIndex(Phone.NUMBER)
            val iT = c.getColumnIndex(Phone.TYPE)
            while (c.moveToNext()) {
                val nome = c.getString(iN) ?: continue
                val num = c.getString(iP) ?: continue
                val tipo = c.getInt(iT)
                val nn = norm(nome)
                val tokens = nn.split(" ")
                if (!q.all { qt -> tokens.any { it.startsWith(qt) } }) continue
                val atual = mapa[nn]
                if (atual == null || (atual.second != Phone.TYPE_MOBILE && tipo == Phone.TYPE_MOBILE)) {
                    mapa[nn] = Contacto(nome, num) to tipo
                }
            }
        }
        return mapa.entries
            .map { e -> Achado(e.value.first, if (e.key == qJunto) 1000 else 500 - e.key.length) }
            .sortedByDescending { it.pontos }
    }

    private fun escolher(l: List<Achado>): Contacto? =
        if (l.size == 1) l[0].contacto else if (l[0].pontos > l[1].pontos) l[0].contacto else null

    private fun numeroLegivel(d: String) = d.chunked(3).joinToString(" ")

    // Devolve (contacto, mensagem de erro/dúvida)
    private fun resolver(alvo: String, explicito: Boolean): Pair<Contacto?, String?> {
        val digitos = alvo.filter { it.isDigit() }
        if (digitos.length >= 9 && alvo.none { it.isLetter() }) {
            return Contacto(numeroLegivel(digitos), digitos) to null
        }
        if (!temContactos()) {
            return null to (if (explicito) "Preciso de acesso aos contactos. Abre ⚙ Configurações → Permissões." else "")
        }
        val achados = procurarContactos(alvo)
        if (achados.isEmpty()) {
            return null to (if (explicito) "Não encontrei \"$alvo\" nos contactos." else "")
        }
        val c = escolher(achados)
        if (c == null) {
            val nomes = achados.take(3).joinToString(", ") { it.contacto.nome }
            return null to "Encontrei vários: $nomes. Diz o nome completo."
        }
        return c to null
    }

    private fun prepararLigacao(alvo: String, explicito: Boolean, voz: Boolean): String? {
        val (c, erro) = resolver(alvo, explicito)
        if (c == null) return if (erro.isNullOrEmpty()) null else erro
        return pedirConfirmacao("Queres que ligue a ${c.nome}?", voz) { ligarPara(c.numero, c.nome) }
    }

    private fun prepararMensagem(alvo: String, texto: String, whats: Boolean, voz: Boolean): String {
        val (c, erro) = resolver(alvo, true)
        if (c == null) return erro ?: "Não encontrei \"$alvo\"."
        val canal = if (whats) "WhatsApp" else "SMS"
        return pedirConfirmacao("Enviar $canal a ${c.nome}: «$texto»?", voz) {
            if (whats) abrirWhatsapp(c.numero, c.nome, texto) else enviarSms(c.numero, c.nome, texto)
        }
    }

    // ---------- Lançar apps (com ou sem ecrã) ----------
    private fun lancar(intent: Intent, descricao: String): Int {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val direto = AssistenteService.appVisivel || Settings.canDrawOverlays(ctx)
        if (direto) {
            try {
                ctx.startActivity(intent)
                return LANCADO
            } catch (e: ActivityNotFoundException) {
                return SEM_APP
            } catch (_: Throwable) {
            }
        }
        return try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel("acoes", "Ações pedidas", NotificationManager.IMPORTANCE_HIGH)
            )
            reqCode++
            val pi = PendingIntent.getActivity(
                ctx, reqCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = Notification.Builder(ctx, "acoes")
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setContentTitle("Toca para continuar")
                .setContentText(descricao)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(20000 + reqCode % 1000, n)
            NOTIFICACAO
        } catch (_: Throwable) {
            SEM_APP
        }
    }

    // ---------- Chamadas ----------
    private fun ligarPara(numero: String, nome: String): String {
        val limpo = numero.filter { it.isDigit() || it == '+' }
        if (ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            try {
                val tm = ctx.getSystemService(TelecomManager::class.java)
                if (tm != null) {
                    tm.placeCall(Uri.fromParts("tel", limpo, null), Bundle())
                    return "A ligar a $nome."
                }
            } catch (_: Throwable) {
            }
        }
        val i = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", limpo, null))
        return when (lancar(i, "Ligar a $nome")) {
            LANCADO -> "Abri o marcador com o número de $nome. Toca em ligar."
            NOTIFICACAO -> "Deixei uma notificação: toca para ligar a $nome."
            else -> "Não encontrei nenhuma app de telefone."
        }
    }

    // ---------- Mensagens ----------
    @Suppress("DEPRECATION")
    private fun enviarSms(numero: String, nome: String, texto: String): String {
        val limpo = numero.filter { it.isDigit() || it == '+' }
        if (ctx.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
            try {
                val sm = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java)
                else SmsManager.getDefault()
                val partes = sm.divideMessage(texto)
                if (partes.size > 1) {
                    sm.sendMultipartTextMessage(limpo, null, partes, null, null)
                } else {
                    sm.sendTextMessage(limpo, null, texto, null, null)
                }
                return "SMS enviado a $nome."
            } catch (e: Throwable) {
                return "Não consegui enviar o SMS: ${e.message}"
            }
        }
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$limpo")).putExtra("sms_body", texto)
        return when (lancar(i, "SMS para $nome")) {
            LANCADO -> "Abri a app de mensagens com o texto pronto. Toca em enviar."
            NOTIFICACAO -> "Deixei uma notificação: toca para abrir a mensagem a $nome."
            else -> "Não encontrei nenhuma app de mensagens."
        }
    }

    // Número em formato internacional, só com dígitos (para o WhatsApp)
    private fun numeroInternacional(numero: String): String {
        val limpo = numero.trim()
        val dig = limpo.filter { it.isDigit() }
        if (limpo.startsWith("+")) return dig
        if (dig.startsWith("00")) return dig.substring(2)
        val pais = (ctx.getSystemService(TelephonyManager::class.java)?.simCountryIso ?: "")
            .uppercase().ifEmpty { Locale.getDefault().country }
        val e164 = try { PhoneNumberUtils.formatNumberToE164(limpo, pais) } catch (_: Throwable) { null }
        if (e164 != null) return e164.filter { it.isDigit() }
        val prefixo = mapOf(
            "PT" to "351", "BR" to "55", "ES" to "34", "FR" to "33", "GB" to "44", "US" to "1"
        )[pais]
        return if (prefixo != null && !dig.startsWith(prefixo)) prefixo + dig.trimStart('0') else dig
    }

    private fun abrirWhatsapp(numero: String, nome: String, texto: String): String {
        val dig = numeroInternacional(numero)
        val enc = Uri.encode(texto)
        val tentativas = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://send?phone=$dig&text=$enc"))
                .setPackage("com.whatsapp"),
            Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://send?phone=$dig&text=$enc"))
                .setPackage("com.whatsapp.w4b"),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$dig?text=$enc"))
        )
        for (i in tentativas) {
            when (lancar(i, "WhatsApp para $nome")) {
                LANCADO -> return "Abri o WhatsApp com a mensagem pronta para $nome. Toca em enviar."
                NOTIFICACAO -> return "Deixei uma notificação: toca para abrir o WhatsApp com $nome."
            }
        }
        return "Não consegui abrir o WhatsApp. Está instalado neste telemóvel?"
    }

    // ---------- Música, média e volume ----------
    private fun notifAcesso(): Boolean {
        val l = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
            ?: return false
        return l.contains(ctx.packageName)
    }

    // Depois de abrir a app, pede à sessão de música que toque o que foi pedido
    private fun tentarControlador(consulta: String, pacote: String?, tentativa: Int, max: Int) {
        ui.postDelayed({
            var feito = false
            try {
                val msm = ctx.getSystemService(MediaSessionManager::class.java)
                val sessoes = msm.getActiveSessions(ComponentName(ctx, OuvinteNotificacoes::class.java))
                    .filter { it.packageName != ctx.packageName }
                val c = sessoes.firstOrNull { pacote != null && it.packageName == pacote }
                    ?: sessoes.firstOrNull()
                if (c != null) {
                    c.transportControls.playFromSearch(consulta, Bundle())
                    feito = true
                }
            } catch (_: Throwable) {
            }
            if (!feito && tentativa + 1 < max) tentarControlador(consulta, pacote, tentativa + 1, max)
        }, 2500)
    }

    private fun tocarMusica(consulta: String, pacote: String?): String {
        if (consulta.isBlank()) {
            teclaMidia(KeyEvent.KEYCODE_MEDIA_PLAY)
            return "Mandei tocar. Se nada tocar, diz o nome de uma música ou artista."
        }
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(SearchManager.QUERY, consulta)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        if (pacote != null) i.setPackage(pacote)
        val r = lancar(i, "Tocar $consulta")
        if (r == SEM_APP) return "Não encontrei nenhuma app de música que aceite este pedido."
        val acesso = notifAcesso()
        if (acesso) tentarControlador(consulta, pacote, 0, if (r == LANCADO) 4 else 20)
        val base = if (r == LANCADO) "A procurar $consulta na tua app de música."
        else "Deixei uma notificação: toca para tocar $consulta."
        return if (acesso) base
        else "$base Para eu a pôr a tocar sozinha, ativa o acesso às notificações em ⚙ Configurações → Permissões."
    }

    private fun teclaMidia(tecla: Int) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        svc.suspenderSessaoMidia()
        ui.postDelayed({
            try {
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, tecla))
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, tecla))
            } catch (_: Throwable) {
            }
            ui.postDelayed({ svc.retomarSessaoMidia() }, 1500)
        }, 150)
    }

    private fun ajustarVolume(subir: Boolean) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        am.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (subir) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
    }

    private fun midiaOuVolume(t: String): String? {
        fun m(r: String) = Regex(r).containsMatchIn(t)
        return when {
            m("""^(?:por favor )?(?:pausa|pausar|stop)(?: a)?(?: musica| som| video)?$""") ||
                m("""^(?:para|parar) (?:a )?(?:musica|som)$""") -> {
                teclaMidia(KeyEvent.KEYCODE_MEDIA_PAUSE)
                "Ok."
            }
            m("""^(?:por favor )?(?:continua|continuar|retoma|retomar|play)(?: a)?(?: musica| som)?$""") -> {
                teclaMidia(KeyEvent.KEYCODE_MEDIA_PLAY)
                "Ok."
            }
            m("""^(?:por favor )?(?:proxima|seguinte|salta|passa|avanca)(?: a)?(?: musica| faixa)?$""") ||
                m("""^musica seguinte$""") || m("""^passa a frente$""") -> {
                teclaMidia(KeyEvent.KEYCODE_MEDIA_NEXT)
                "Ok."
            }
            m("""^(?:por favor )?(?:anterior|volta atras)(?: musica| faixa)?$""") ||
                m("""^musica anterior$""") -> {
                teclaMidia(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                "Ok."
            }
            m("""\b(?:aumenta|sobe|mais) (?:o )?(?:volume|som)\b""") || m("""\bmais alto\b""") -> {
                ajustarVolume(true)
                "Volume aumentado."
            }
            m("""\b(?:baixa|diminui|reduz|menos) (?:o )?(?:volume|som)\b""") || m("""\bmais baixo\b""") -> {
                ajustarVolume(false)
                "Volume reduzido."
            }
            else -> null
        }
    }
}
