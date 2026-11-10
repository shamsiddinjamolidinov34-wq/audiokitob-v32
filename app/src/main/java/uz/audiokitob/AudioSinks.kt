package uz.audiokitob

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * jump3r (LAME ning sof Java porti) bilan PCM → MP3.
 * Sinf va metodlarga reflection orqali murojaat qilinadi: kutubxona ichki API si oʻzgarsa ham ilova yigʻilaveradi,
 * ishlamasa — audio WAV formatida saqlanadi (jurnalga yoziladi).
 */
class LameReflect(rate: Int, bitrate: Int, quality: Int) {
    private val enc: Any
    private val mEncode: Method
    private val mFinish: Method
    private val mClose: Method
    val bufSize: Int

    init {
        val cls = Class.forName("de.sciss.jump3r.lowlevel.LameEncoder")
        // javax.sound.sampled Androidda yoʻq, shuning uchun Kotlin kodi unga kompilyatsiya vaqtida bogʻlanmaydi.
        // AudioFormat sinfi ish vaqtida APK ichidagi Java qolipdan olinadi (app/src/main/java/javax/sound/sampled/).
        val fmtCls = Class.forName("javax.sound.sampled.AudioFormat")
        val encCls = Class.forName("javax.sound.sampled.AudioFormat\$Encoding")
        val pcmSigned = encCls.getField("PCM_SIGNED").get(null)
        val intT = Int::class.javaPrimitiveType
        val boolT = Boolean::class.javaPrimitiveType
        val floatT = Float::class.javaPrimitiveType
        val fmt = fmtCls.getConstructor(encCls, floatT, intT, intT, intT, floatT, boolT)
            .newInstance(pcmSigned, rate.toFloat(), 16, 1, 2, rate.toFloat(), false)
        var o: Any? = null
        try {
            val c5 = cls.getConstructor(fmtCls, intT, intT, intT, boolT)
            o = c5.newInstance(fmt, bitrate, -1, quality, false)
        } catch (e: NoSuchMethodException) {
            Journal.w("Mp3", "5 parametrli LameEncoder konstruktori yoʻq, oddiy konstruktor ishlatiladi")
            val c1 = cls.getConstructor(fmtCls)
            o = c1.newInstance(fmt)
        }
        enc = o!!
        mEncode = cls.getMethod("encodeBuffer", ByteArray::class.java, intT, intT, ByteArray::class.java)
        mFinish = cls.getMethod("encodeFinish", ByteArray::class.java)
        mClose = cls.getMethod("close")
        bufSize = (cls.getMethod("getPCMBufferSize").invoke(enc) as Number).toInt()
    }

    private fun <T> unwrap(f: () -> T): T {
        try {
            return f()
        } catch (e: InvocationTargetException) {
            throw (e.cause ?: e)
        }
    }

    fun encode(pcm: ByteArray, off: Int, len: Int, out: ByteArray): Int =
        unwrap { (mEncode.invoke(enc, pcm, off, len, out) as Number).toInt() }

    fun finish(out: ByteArray): Int = unwrap { (mFinish.invoke(enc, out) as Number).toInt() }

    fun close() {
        try {
            mClose.invoke(enc)
        } catch (t: Throwable) {
        }
    }
}

object Mp3Writer {
    /** Bir boʻlak PCM (16-bit mono) ni toʻliq MP3 baytlariga aylantiradi (128 kbit/s, sifat 3). */
    fun encode(pcm: ByteArray, rate: Int): ByteArray {
        val enc = LameReflect(rate, 128, 3)
        try {
            val bos = ByteArrayOutputStream(pcm.size / 8 + 8192)
            val buf = ByteArray(64 * 1024)
            var step = enc.bufSize and 1.inv()
            if (step < 2) step = 32768
            var pos = 0
            while (pos < pcm.size) {
                val len = minOf(step, pcm.size - pos)
                val n = enc.encode(pcm, pos, len, buf)
                if (n > 0) bos.write(buf, 0, n)
                pos += len
            }
            val f = enc.finish(buf)
            if (f > 0) bos.write(buf, 0, f)
            return bos.toByteArray()
        } finally {
            enc.close()
        }
    }
}

/** Zaxira: MP3 kodeki ishlamasa audio WAV (16-bit mono) formatida saqlanadi. */
object WavWriter {
    private fun header(rate: Int, dataLen: Long): ByteArray {
        val bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.ISO_8859_1))
        bb.putInt((36L + dataLen).toInt())
        bb.put("WAVE".toByteArray(Charsets.ISO_8859_1))
        bb.put("fmt ".toByteArray(Charsets.ISO_8859_1))
        bb.putInt(16)
        bb.putShort(1.toShort())
        bb.putShort(1.toShort())
        bb.putInt(rate)
        bb.putInt(rate * 2)
        bb.putShort(2.toShort())
        bb.putShort(16.toShort())
        bb.put("data".toByteArray(Charsets.ISO_8859_1))
        bb.putInt(dataLen.toInt())
        return bb.array()
    }

    fun append(file: File, pcm: ByteArray, rate: Int) {
        val raf = RandomAccessFile(file, "rw")
        try {
            if (raf.length() < 44L) {
                raf.setLength(0L)
                raf.write(header(rate, 0L))
            }
            raf.seek(raf.length())
            raf.write(pcm)
            val dataLen = raf.length() - 44L
            raf.seek(0L)
            raf.write(header(rate, dataLen))
            try {
                raf.fd.sync()
            } catch (t: Throwable) {
            }
        } finally {
            raf.close()
        }
    }
}
