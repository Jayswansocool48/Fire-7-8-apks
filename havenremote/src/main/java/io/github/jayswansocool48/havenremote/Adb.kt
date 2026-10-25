package io.github.jayswansocool48.havenremote

import android.content.Context
import android.util.Base64
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import kotlin.concurrent.thread

/**
 * The phone's ADB identity. The TV asks once to "allow" it; after that it's trusted.
 * Stored in the app's private files so it survives restarts.
 */
class AdbKey private constructor(private val priv: PrivateKey, private val pub: RSAPublicKey) {

    /** Signs the TV's 20-byte challenge the way adb does (PKCS#1 v1.5 with a SHA-1 header). */
    fun sign(token: ByteArray): ByteArray {
        val c = Cipher.getInstance("RSA/ECB/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, priv)
        c.update(SIGNATURE_PADDING)
        return c.doFinal(token)
    }

    /** The public key in adb's own format, as the TV stores it in its trusted list. */
    fun adbPublicKey(name: String): ByteArray {
        val n = pub.modulus
        val r32 = BigInteger.ONE.shiftLeft(32)
        val rr = BigInteger.ONE.shiftLeft(KEY_BITS).modPow(BigInteger.valueOf(2), n)
        val n0inv = r32.subtract(n.remainder(r32).modInverse(r32))
        val buf = ByteBuffer.allocate(4 + 4 + KEY_BYTES + KEY_BYTES + 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(KEY_BYTES / 4)
        buf.putInt(n0inv.toInt())
        var x = n
        for (i in 0 until KEY_BYTES / 4) {
            buf.putInt(x.remainder(r32).toInt())
            x = x.shiftRight(32)
        }
        var y = rr
        for (i in 0 until KEY_BYTES / 4) {
            buf.putInt(y.remainder(r32).toInt())
            y = y.shiftRight(32)
        }
        buf.putInt(pub.publicExponent.toInt())
        val b64 = Base64.encodeToString(buf.array(), Base64.NO_WRAP)
        return "$b64 $name\u0000".toByteArray(Charsets.UTF_8)
    }

    companion object {
        private const val KEY_BITS = 2048
        private const val KEY_BYTES = KEY_BITS / 8

        private val SIGNATURE_PADDING: ByteArray = run {
            val digestInfo = byteArrayOf(
                0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
            )
            val p = ByteArray(KEY_BYTES - 20)
            p[0] = 0x00
            p[1] = 0x01
            for (i in 2 until p.size - digestInfo.size - 1) p[i] = 0xFF.toByte()
            p[p.size - digestInfo.size - 1] = 0x00
            System.arraycopy(digestInfo, 0, p, p.size - digestInfo.size, digestInfo.size)
            p
        }

        @Volatile private var cached: AdbKey? = null

        @Synchronized
        fun get(ctx: Context): AdbKey {
            cached?.let { return it }
            val privFile = File(ctx.filesDir, "adbkey")
            val pubFile = File(ctx.filesDir, "adbkey.pub")
            val kf = KeyFactory.getInstance("RSA")
            var key: AdbKey? = null
            if (privFile.exists() && pubFile.exists()) {
                try {
                    key = AdbKey(
                        kf.generatePrivate(PKCS8EncodedKeySpec(privFile.readBytes())),
                        kf.generatePublic(X509EncodedKeySpec(pubFile.readBytes())) as RSAPublicKey,
                    )
                } catch (e: Exception) {
                    key = null
                }
            }
            if (key == null) {
                val gen = KeyPairGenerator.getInstance("RSA")
                gen.initialize(KEY_BITS)
                val pair = gen.genKeyPair()
                privFile.writeBytes(pair.private.encoded)
                pubFile.writeBytes(pair.public.encoded)
                key = AdbKey(pair.private, pair.public as RSAPublicKey)
            }
            cached = key
            return key
        }
    }
}

/**
 * Just enough of the adb wire protocol to run shell commands on the TV over Wi-Fi
 * (Fire TV listens on port 5555 once "ADB debugging" is on).
 */
class AdbConnection private constructor(private val socket: Socket) {

    private val input = DataInputStream(socket.getInputStream().buffered())
    private val output: OutputStream = socket.getOutputStream()
    private val streams = ConcurrentHashMap<Int, AdbStream>()
    private val nextId = AtomicInteger(1)
    @Volatile var closed = false
        private set
    var onClosed: (() -> Unit)? = null

    inner class AdbStream(val localId: Int, private val keepOutput: Boolean) {
        @Volatile var remoteId = 0
        @Volatile var refused = false
        val opened = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val acks = Semaphore(0)
        private val out = StringBuilder()

        fun append(data: ByteArray) {
            if (keepOutput) synchronized(out) { out.append(String(data, Charsets.UTF_8)) }
        }

        fun output(): String = synchronized(out) { out.toString() }

        /** Sends bytes into the stream (for an open shell) and waits for the TV to take them. */
        fun write(data: ByteArray) {
            if (closed || finished.count == 0L) throw IOException("stream closed")
            send(A_WRTE, localId, remoteId, data)
            if (!acks.tryAcquire(5, TimeUnit.SECONDS)) throw IOException("TV stopped answering")
        }

        fun close() {
            if (finished.count > 0L && !closed) {
                try {
                    send(A_CLSE, localId, remoteId, ByteArray(0))
                } catch (e: IOException) {
                }
            }
            finished.countDown()
            streams.remove(localId)
        }
    }

    /** Opens a service on the TV, like "shell:input keyevent 3" or plain "shell:" for a session. */
    fun open(destination: String, keepOutput: Boolean = true): AdbStream {
        val id = nextId.getAndIncrement()
        val s = AdbStream(id, keepOutput)
        streams[id] = s
        send(A_OPEN, id, 0, (destination + "\u0000").toByteArray(Charsets.UTF_8))
        if (!s.opened.await(6, TimeUnit.SECONDS) || s.refused) {
            streams.remove(id)
            throw IOException("TV refused $destination")
        }
        return s
    }

    /** Runs one shell command and returns what it printed. */
    fun shell(command: String, timeoutSec: Long = 15): String {
        val s = open("shell:$command")
        s.finished.await(timeoutSec, TimeUnit.SECONDS)
        s.close()
        return s.output()
    }

    fun close() {
        if (closed) return
        closed = true
        try {
            socket.close()
        } catch (e: IOException) {
        }
        for (s in streams.values) {
            s.refused = true
            s.opened.countDown()
            s.finished.countDown()
            s.acks.release(100)
        }
        streams.clear()
        onClosed?.invoke()
    }

    private fun startReader() {
        thread(name = "adb-reader", isDaemon = true) {
            try {
                while (!closed) {
                    val m = read(input)
                    when (m.cmd) {
                        A_OKAY -> {
                            val s = streams[m.arg1] ?: continue
                            if (s.remoteId == 0) {
                                s.remoteId = m.arg0
                                s.opened.countDown()
                            } else {
                                s.acks.release()
                            }
                        }
                        A_WRTE -> {
                            val s = streams[m.arg1]
                            if (s != null) {
                                s.append(m.data)
                                send(A_OKAY, s.localId, m.arg0, ByteArray(0))
                            }
                        }
                        A_CLSE -> {
                            val s = streams.remove(m.arg1) ?: continue
                            if (s.remoteId == 0) s.refused = true
                            s.opened.countDown()
                            s.finished.countDown()
                            s.acks.release(100)
                        }
                    }
                }
            } catch (e: Exception) {
            } finally {
                close()
            }
        }
    }

    private fun send(cmd: Int, a0: Int, a1: Int, data: ByteArray) {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0
        for (b in data) sum += b.toInt() and 0xFF
        header.putInt(cmd).putInt(a0).putInt(a1).putInt(data.size).putInt(sum).putInt(cmd.inv())
        synchronized(output) {
            output.write(header.array())
            if (data.isNotEmpty()) output.write(data)
            output.flush()
        }
    }

    private class Msg(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    companion object {
        const val PORT = 5555
        private const val A_CNXN = 0x4e584e43
        private const val A_AUTH = 0x48545541
        private const val A_OPEN = 0x4e45504f
        private const val A_OKAY = 0x59414b4f
        private const val A_CLSE = 0x45534c43
        private const val A_WRTE = 0x45545257
        private const val AUTH_TOKEN = 1
        private const val AUTH_SIGNATURE = 2
        private const val AUTH_RSAPUBLICKEY = 3
        private const val VERSION = 0x01000000
        private const val MAX_DATA = 4096

        private fun read(input: DataInputStream): Msg {
            val head = ByteArray(24)
            input.readFully(head)
            val b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            val cmd = b.int
            val a0 = b.int
            val a1 = b.int
            val len = b.int
            b.int // checksum
            b.int // magic
            if (len < 0 || len > 1024 * 1024) throw IOException("bad adb packet")
            val data = ByteArray(len)
            input.readFully(data)
            return Msg(cmd, a0, a1, data)
        }

        /**
         * Connects and logs in. [onApprove] is called when the TV is showing its
         * "Allow USB debugging?" box, so the app can tell the user to press OK on the TV.
         */
        fun connect(ctx: Context, host: String, onApprove: () -> Unit): AdbConnection {
            val key = AdbKey.get(ctx)
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, PORT), 4000)
            socket.soTimeout = 8000
            val conn = AdbConnection(socket)
            try {
                conn.send(A_CNXN, VERSION, MAX_DATA, "host::haven-remote\u0000".toByteArray())
                var signed = false
                while (true) {
                    val m = read(conn.input)
                    when (m.cmd) {
                        A_AUTH -> if (m.arg0 == AUTH_TOKEN) {
                            if (!signed) {
                                signed = true
                                conn.send(A_AUTH, AUTH_SIGNATURE, 0, key.sign(m.data))
                            } else {
                                conn.send(A_AUTH, AUTH_RSAPUBLICKEY, 0, key.adbPublicKey("haven-remote@${android.os.Build.MODEL.replace(' ', '_')}"))
                                onApprove()
                                // Give the user time to find the remote and press OK.
                                socket.soTimeout = 90_000
                            }
                        }
                        A_CNXN -> {
                            socket.soTimeout = 0
                            conn.startReader()
                            return conn
                        }
                    }
                }
            } catch (e: Exception) {
                try {
                    socket.close()
                } catch (e2: IOException) {
                }
                throw e
            }
        }
    }
}
