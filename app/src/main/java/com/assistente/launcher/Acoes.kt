package com.assistente.launcher

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object Acoes {
    const val ACAO_ALARME = "com.assistente.launcher.ALARME"

    private data class Hora(val h: Int, val m: Int, val qualificada: Boolean)

    private val dias = arrayOf(
        "domingo", "segunda-feira", "terça-feira", "quarta-feira",
        "quinta-feira", "sexta-feira", "sábado"
    )
    private val meses = arrayOf(
        "janeiro", "fevereiro", "março", "abril", "maio", "junho",
        "julho", "agosto", "setembro", "outubro", "novembro", "dezembro"
    )
    private val palavras = mapOf(
        "um" to 1, "uma" to 1, "dois" to 2, "duas" to 2, "tres" to 3, "quatro" to 4,
        "cinco" to 5, "seis" to 6, "sete" to 7, "oito" to 8, "nove" to 9, "dez" to 10,
        "onze" to 11, "doze" to 12, "treze" to 13, "catorze" to 14, "quinze" to 15,
        "dezasseis" to 16, "dezassete" to 17, "dezoito" to 18, "dezanove" to 19,
        "vinte" to 20, "trinta" to 30, "quarenta" to 40, "cinquenta" to 50
    )

    private val reHM = Regex("""\b(\d{1,2})\s*(?::|h)\s*(\d{2})\b""")
    private val reE = Regex("""\b(\d{1,2})\s*(?:horas?|h)?\s+e\s+(meia|\d{1,2}\s+quartos?|\d{1,2})\b""")
    private val reAs = Regex("""\bas\s+(\d{1,2})\b""")
    private val reHoras = Regex("""\b(\d{1,2})\s*(?:horas?|h)\b""")

    private val reNota = Regex(
        """^\s*(?:anota(?:r)?(?:-me)?|aponta(?:r)?(?:-me)?|toma(?:r)?\s+nota|escreve(?:r)?\s+uma\s+nota|guarda(?:r)?\s+uma\s+nota|cria(?:r)?\s+uma\s+nota|nova\s+nota|nota)\b[\s,:;.-]*(?:que\s+|de\s+|sobre\s+|para\s+)?(.*)$""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    // ---------- Texto ----------
    fun norm(s: String): String {
        val n = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        return n.replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9: ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun comDigitos(t: String) =
        t.split(" ").joinToString(" ") { palavras[it]?.toString() ?: it }

    private fun fmt(h: Int, m: Int) = String.format(Locale.US, "%02d:%02d", h, m)

    private fun parseHora(tn: String): Hora? {
        if (tn.contains("meio dia")) return Hora(12, 0, true)
        if (tn.contains("meia noite")) return Hora(0, 0, true)
        val qual = Regex("""\b(?:da|de) (manha|tarde|noite|madrugada)\b""")
            .find(tn)?.groupValues?.get(1)
        var h = 0
        var m = 0
        val a = reHM.find(tn)
        val b = reE.find(tn)
        val c = reAs.find(tn)
        val d = reHoras.find(tn)
        when {
            a != null -> {
                h = a.groupValues[1].toInt()
                m = a.groupValues[2].toInt()
            }
            b != null -> {
                h = b.groupValues[1].toInt()
                val g = b.groupValues[2]
                m = when {
                    g == "meia" -> 30
                    g.contains("quarto") -> 15
                    else -> g.toInt()
                }
            }
            c != null -> h = c.groupValues[1].toInt()
            d != null -> h = d.groupValues[1].toInt()
            else -> return null
        }
        if (qual == "tarde" || qual == "noite") {
            if (h in 1..11) h += 12
            if (qual == "noite" && h == 12) h = 0
        }
        if ((qual == "manha" || qual == "madrugada") && h == 12) h = 0
        if (h > 23 || m > 59) return null
        return Hora(h, m, qual != null)
    }

    private fun parseDuracao(tn: String): Int? {
        var total = 0
        var achou = false
        Regex("""\b(\d+)\s*(segundos?|segs?|minutos?|mins?|horas?)\b""").findAll(tn).forEach {
            val n = it.groupValues[1].toInt()
            val u = it.groupValues[2]
            total += when {
                u.startsWith("seg") -> n
                u.startsWith("min") -> n * 60
                else -> n * 3600
            }
            achou = true
        }
        if (Regex("""\bhoras? e meia\b""").containsMatchIn(tn)) {
            total += 1800; achou = true
        } else if (Regex("""\bmeia hora\b""").containsMatchIn(tn)) {
            total += 1800; achou = true
        }
        if (Regex("""\b1 quarto de hora\b""").containsMatchIn(tn)) {
            total += 900; achou = true
        }
        return if (achou && total > 0) total else null
    }

    private fun limparMensagem(original: String): String {
        var s = original
        val rem = listOf(
            """(?i)\b(lembra|lembrar|avisa|avisar)(-me|\s+me)?\b""",
            """(?i)\b(alarme|despertador|temporizador|timer|cron[oó]metro)\b""",
            """(?i)\b(acorda|acordar|desperta|despertar)(-me|\s+me)?\b""",
            """(?i)\b(daqui a|dentro de)\s+meia hora""",
            """(?i)\b(daqui a|dentro de|em)\s+(\d+|um|uma|dois|duas|tr[eê]s|quatro|cinco|seis|sete|oito|nove|dez|quinze|vinte|trinta)\s*(segundos?|minutos?|horas?)(\s+e\s+meia)?""",
            """(?i)\b(para as|para a|[àa]s|[àa])\s*\d{1,2}\s*(?:[:h]\s*\d{2}|h|horas?)?(\s+e\s+(meia|\d{1,2}))?""",
            """(?i)\b(da|de)\s+(manh[ãa]|tarde|noite|madrugada)\b""",
            """(?i)\b(amanh[ãa]|hoje)\b""",
            """(?i)\b(ao\s+)?meio[- ]dia\b""",
            """(?i)\bmeia[- ]noite\b"""
        )
        rem.forEach { s = Regex(it).replace(s, " ") }
        s = s.replace(Regex("\\s+"), " ").trim().trim(',', ':', ';', '.', ' ')
        s = s.replace(Regex("(?i)^(de|que|para|a|o)\\s+"), "")
        return s.trim()
    }

    // ---------- Armazenamento ----------
    private fun prefs(c: Context) = c.getSharedPreferences("alarmes", Context.MODE_PRIVATE)

    fun lerAlarmes(c: Context): MutableList<JSONObject> {
        val arr = try { JSONArray(prefs(c).getString("lista", "[]")) } catch (_: Exception) { JSONArray() }
        val agora = System.currentTimeMillis()
        val out = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getLong("quando") > agora) out.add(o)
        }
        return out
    }

    private fun gravar(c: Context, l: List<JSONObject>) {
        val arr = JSONArray()
        l.forEach { arr.put(it) }
        prefs(c).edit().putString("lista", arr.toString()).apply()
    }

    fun removerDisparado(c: Context, id: Int) {
        val l = try {
            val arr = JSONArray(prefs(c).getString("lista", "[]"))
            (0 until arr.length()).map { arr.getJSONObject(it) }
        } catch (_: Exception) {
            emptyList()
        }
        gravar(c, l.filter { it.getInt("id") != id })
    }

    private fun agendar(c: Context, quando: Long, texto: String, tipo: String) {
        val id = (System.currentTimeMillis() % 1_000_000_000L).toInt()
        val o = JSONObject().put("id", id).put("quando", quando).put("texto", texto).put("tipo", tipo)
        val l = lerAlarmes(c)
        l.add(o)
        gravar(c, l)
        armar(c, o)
    }

    private fun armar(c: Context, o: JSONObject) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val i = Intent(c, AlarmeReceiver::class.java)
            .setAction(ACAO_ALARME)
            .putExtra("id", o.getInt("id"))
            .putExtra("texto", o.getString("texto"))
            .putExtra("tipo", o.getString("tipo"))
        val pi = PendingIntent.getBroadcast(
            c, o.getInt("id"), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val mostrar = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(o.getLong("quando"), mostrar), pi)
    }

    private fun desarmar(c: Context, id: Int) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            c, id, Intent(c, AlarmeReceiver::class.java).setAction(ACAO_ALARME),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pi != null) {
            am.cancel(pi)
            pi.cancel()
        }
    }

    fun rearmar(c: Context) {
        lerAlarmes(c).forEach { armar(c, it) }
    }

    // ---------- Notas ----------
    private fun ficheiroNotas(c: Context) = File(c.filesDir, "notas.txt")

    private fun guardarNota(c: Context, texto: String) {
        val data = SimpleDateFormat("dd/MM HH:mm", Locale("pt", "PT")).format(Date())
        ficheiroNotas(c).appendText("$data | ${texto.replace("\n", " ")}\n")
    }

    private fun lerNotas(c: Context): List<String> {
        val f = ficheiroNotas(c)
        if (!f.exists()) return emptyList()
        return f.readLines().filter { it.isNotBlank() }
    }

    private fun lerNotasTxt(c: Context): String {
        val l = lerNotas(c)
        if (l.isEmpty()) return "Ainda não tens notas."
        val ultimas = l.takeLast(5).reversed()
        val corpo = ultimas.mapIndexed { i, s -> "${i + 1}: ${s.substringAfter(" | ")}" }
            .joinToString(". ")
        val total = if (l.size == 1) "Tens 1 nota." else "Tens ${l.size} notas."
        return if (l.size > 5) "$total As mais recentes. $corpo." else "$total $corpo."
    }

    // ---------- Datas ----------
    private fun mesmoDia(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    private fun quandoTxt(ts: Long): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = ts
        val hoje = Calendar.getInstance()
        val amanha = Calendar.getInstance()
        amanha.add(Calendar.DAY_OF_YEAR, 1)
        val hm = fmt(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        return when {
            mesmoDia(cal, hoje) -> "às $hm"
            mesmoDia(cal, amanha) -> "amanhã às $hm"
            else -> "dia ${cal.get(Calendar.DAY_OF_MONTH)}/${cal.get(Calendar.MONTH) + 1} às $hm"
        }
    }

    private fun duracaoTxt(s: Int): String {
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        val p = mutableListOf<String>()
        if (h > 0) p.add(if (h == 1) "1 hora" else "$h horas")
        if (m > 0) p.add(if (m == 1) "1 minuto" else "$m minutos")
        if (sec > 0) p.add(if (sec == 1) "1 segundo" else "$sec segundos")
        return p.joinToString(" e ")
    }

    private fun proximaOcorrencia(h: Hora, amanha: Boolean, ambiguo: Boolean): Long {
        fun cal(hora: Int): Calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hora)
            set(Calendar.MINUTE, h.m)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val agora = System.currentTimeMillis()
        val candidatos = mutableListOf(cal(h.h))
        if (ambiguo && !h.qualificada && h.h in 1..11) candidatos.add(cal(h.h + 12))
        if (amanha) {
            candidatos.forEach { it.add(Calendar.DAY_OF_YEAR, 1) }
        } else {
            candidatos.forEach { if (it.timeInMillis <= agora) it.add(Calendar.DAY_OF_YEAR, 1) }
        }
        return candidatos.minOf { it.timeInMillis }
    }

    private fun horasAgora(): String {
        val c = Calendar.getInstance()
        val h = c.get(Calendar.HOUR_OF_DAY)
        val m = c.get(Calendar.MINUTE)
        val verbo = if (h == 1) "É" else "São"
        return "$verbo ${fmt(h, m)}."
    }

    private fun dataHoje(): String {
        val c = Calendar.getInstance()
        val d = dias[c.get(Calendar.DAY_OF_WEEK) - 1]
        return "Hoje é $d, ${c.get(Calendar.DAY_OF_MONTH)} de ${meses[c.get(Calendar.MONTH)]} de ${c.get(Calendar.YEAR)}."
    }

    private fun listar(c: Context): String {
        val l = lerAlarmes(c).sortedBy { it.getLong("quando") }
        if (l.isEmpty()) return "Não tens alarmes nem lembretes ativos."
        val partes = l.map { o ->
            val q = quandoTxt(o.getLong("quando"))
            when (o.getString("tipo")) {
                "alarme" -> "alarme $q"
                "timer" -> "temporizador $q"
                else -> "lembrete $q: ${o.optString("texto")}"
            }
        }
        return "Tens ${l.size}: " + partes.joinToString(". ") + "."
    }

    private fun cancelar(c: Context, tn: String): String {
        val l = lerAlarmes(c)
        if (l.isEmpty()) return "Não tens alarmes nem lembretes ativos."
        val hora = parseHora(tn)
        val alvo = if (hora != null) {
            l.filter {
                val cal = Calendar.getInstance()
                cal.timeInMillis = it.getLong("quando")
                cal.get(Calendar.HOUR_OF_DAY) == hora.h && cal.get(Calendar.MINUTE) == hora.m
            }
        } else l
        if (alvo.isEmpty()) {
            return "Não encontrei nenhum às ${fmt(hora?.h ?: 0, hora?.m ?: 0)}."
        }
        alvo.forEach { desarmar(c, it.getInt("id")) }
        gravar(c, l.filter { it !in alvo })
        return if (alvo.size == 1) "Cancelei 1 alarme ou lembrete."
        else "Cancelei ${alvo.size} alarmes e lembretes."
    }

    // ---------- Reconhecimento de pedidos ----------
    fun tentar(c: Context, original: String): String? {
        val t = norm(original)
        if (t.isEmpty()) return null
        val tn = comDigitos(t)

        // horas e data
        if (t.length <= 40) {
            if ((t.contains("que horas") &&
                    !Regex("""\b(a|ate|desde|por) que horas\b""").containsMatchIn(t)) ||
                Regex("""\b(diz|diga|dizer) me as horas\b""").containsMatchIn(t) || t == "horas"
            ) return horasAgora()
            if (t.contains("que dia e hoje") || t.contains("em que dia estamos") ||
                t.contains("a que dia estamos") || t.contains("data de hoje") ||
                t.contains("que data e hoje")
            ) return dataHoje()
        }

        // notas
        if (t.contains("notas") &&
            Regex("""\b(le|ler|mostra|mostrar|quais|diz|dizer|ouvir|ver|abre)\b""").containsMatchIn(t)
        ) return lerNotasTxt(c)
        val nm = reNota.find(original)
        if (nm != null) {
            val conteudo = nm.groupValues[1].trim()
            if (conteudo.isEmpty()) return "O que queres que eu anote? Diz, por exemplo: anota comprar pão."
            guardarNota(c, conteudo)
            return "Nota guardada: $conteudo."
        }

        // listar e cancelar
        val temAlvo = Regex("""\b(alarmes?|lembretes?|temporizadores?|timers?|despertadores?)\b""")
            .containsMatchIn(t)
        if (temAlvo && Regex("""\b(cancela|cancelar|apaga|apagar|remove|remover|elimina|eliminar|desativa|desativar)\b""")
                .containsMatchIn(t)
        ) return cancelar(c, tn)
        if (Regex("""\b(que|quais) (sao )?(os |as )?(meus |minhas )?(alarmes|lembretes|temporizadores|timers)\b|\b(lista|listar|mostra|mostrar) (me )?(os |as )?(meus |minhas )?(alarmes|lembretes)|\btenho (algum )?(alarme|lembrete)""")
                .containsMatchIn(t)
        ) return listar(c)

        // agendar
        val pedeLembrete = Regex("""\b(lembra|lembrar|avisa|avisar) me\b""").containsMatchIn(t)
        val pedeAlarme = Regex("""\b(alarme|despertador|acorda me|acordar me|desperta me|despertar me)\b""")
            .containsMatchIn(t)
        val pedeTimer = Regex("""\b(temporizador|timer|cronometro)\b""").containsMatchIn(t)
        if (!pedeLembrete && !pedeAlarme && !pedeTimer) return null

        val relativo = Regex("""\b(daqui a|dentro de)\b""").containsMatchIn(t) ||
            Regex("""\bem \d+ (segundos?|minutos?|horas?)\b""").containsMatchIn(tn) || pedeTimer
        val dur = if (relativo) parseDuracao(tn) else null
        if (dur != null) {
            val tipo = when {
                pedeLembrete -> "lembrete"
                pedeAlarme -> "alarme"
                else -> "timer"
            }
            val msg = if (tipo == "lembrete") limparMensagem(original) else ""
            val texto = when {
                tipo == "lembrete" -> if (msg.isNotEmpty()) msg else "Lembrete"
                tipo == "alarme" -> "Alarme"
                else -> "Temporizador terminado"
            }
            agendar(c, System.currentTimeMillis() + dur * 1000L, texto, tipo)
            return when (tipo) {
                "lembrete" -> "Combinado. Aviso-te daqui a ${duracaoTxt(dur)}."
                "alarme" -> "Alarme definido para daqui a ${duracaoTxt(dur)}."
                else -> "Temporizador de ${duracaoTxt(dur)} iniciado."
            }
        }

        val hora = parseHora(tn)
        if (hora != null) {
            val tipo = if (pedeLembrete) "lembrete" else "alarme"
            val amanha = t.contains("amanha")
            val ts = proximaOcorrencia(hora, amanha, tipo == "lembrete")
            val msg = if (tipo == "lembrete") limparMensagem(original) else ""
            val texto = when {
                tipo == "alarme" -> "Alarme"
                msg.isNotEmpty() -> msg
                else -> "Lembrete"
            }
            agendar(c, ts, texto, tipo)
            return if (tipo == "alarme") "Alarme marcado ${quandoTxt(ts)}."
            else "Combinado. Lembro-te ${quandoTxt(ts)}."
        }

        if (pedeTimer) return "Quanto tempo? Diz, por exemplo: temporizador de 10 minutos."
        if (pedeAlarme && Regex("""\b(poe|poes|por|define|definir|cria|criar|marca|marcar|programa|programar|configura|configurar|ativa|ativar|acorda|desperta)\b""")
                .containsMatchIn(t)
        ) return "Para que horas? Diz, por exemplo: alarme às 7 e meia."
        if (pedeLembrete) {
            val msg = limparMensagem(original)
            if (msg.isNotEmpty()) {
                guardarNota(c, msg)
                return "Não percebi quando, por isso guardei como nota: $msg."
            }
        }
        return null
    }
}
