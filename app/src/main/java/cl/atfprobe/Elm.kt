package cl.atfprobe

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Cliente mínimo ELM327 por Bluetooth clásico (SPP). Solo envía lecturas. */
@SuppressLint("MissingPermission")
class Elm {
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    fun connect(device: BluetoothDevice) {
        val s = device.createRfcommSocketToServiceRecord(
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        )
        s.connect()
        socket = s
        input = s.inputStream
        output = s.outputStream
    }

    fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    /** Envía un comando y devuelve la respuesta cruda, sin eco ni el prompt '>' */
    fun send(cmd: String, timeoutMs: Long = 1500): String {
        val out = output ?: throw IllegalStateException("No conectado")
        val inp = input ?: throw IllegalStateException("No conectado")
        while (inp.available() > 0) inp.read()
        out.write((cmd + "\r").toByteArray())
        out.flush()
        val sb = StringBuilder()
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (inp.available() > 0) {
                val c = inp.read()
                if (c == '>'.code) break
                sb.append(c.toChar())
            } else {
                Thread.sleep(5)
            }
        }
        return sb.toString().replace("\r", "\n").lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals(cmd, ignoreCase = true) }
            .joinToString("\n")
    }

    fun responseId(header: String): String =
        (header.toInt(16) + 8).toString(16).uppercase().padStart(3, '0')

    /** Inicializa el ELM para CAN 11 bit / 500 kbps apuntando a un módulo (ej. TCM = 7E1). */
    fun init(header: String): String {
        val log = StringBuilder()
        val cmds = listOf(
            "ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP6", "ATCAF1", "ATAT1", "ATST20",
            "ATSH$header", "ATCRA${responseId(header)}"
        )
        for (c in cmds) {
            val r = send(c, if (c == "ATZ") 4000 else 1500)
            log.append("$c -> ${r.replace("\n", " | ")}\n")
        }
        return log.toString().trimEnd()
    }

    companion object {
        /**
         * Convierte la respuesta del ELM en una cadena hex continua.
         * Devuelve null si hay texto de error (NO DATA, CAN ERROR, ?, etc.).
         */
        fun parse(raw: String): String? {
            val parts = ArrayList<String>()
            for (line in raw.lines()) {
                val l = line.replace(" ", "").uppercase()
                if (l.isEmpty()) continue
                if (!l.matches(Regex("^([0-9A-F]:)?[0-9A-F]+$"))) return null
                if (l.matches(Regex("^[0-9A-F]{3}$"))) continue // línea de longitud (multi-trama)
                parts.add(l.replace(Regex("^[0-9A-F]:"), ""))
            }
            return if (parts.isEmpty()) null else parts.joinToString("")
        }
    }
}
