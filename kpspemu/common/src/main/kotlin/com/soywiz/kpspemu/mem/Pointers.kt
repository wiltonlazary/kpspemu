package com.soywiz.kpspemu.mem

import com.soywiz.korio.error.invalidOp
import com.soywiz.korio.lang.Charset
import com.soywiz.korio.lang.Charsets
import com.soywiz.korio.lang.format
import com.soywiz.korio.stream.ByteArrayBuilderSmall
import com.soywiz.korio.stream.SyncStream
import com.soywiz.korio.stream.SyncStreamBase
import com.soywiz.korio.stream.toSyncStream
import com.soywiz.korio.util.unsigned
import com.soywiz.kpspemu.util.Struct
import com.soywiz.kpspemu.util.read
import com.soywiz.kpspemu.util.write

data class PtrArray(val ptr: Ptr, val size: Int) {
	val addr: Int get() = ptr.addr
	val low: Int get() = ptr.addr
	val high: Int get() = low + size
}

class Ptr32(val ptr: Ptr) {
	fun get(): Int = this[0]
	fun set(value: Int) = run { this[0] = value }
	operator fun get(index: Int): Int = ptr.lw(index * 4)
	operator fun set(index: Int, value: Int) = ptr.sw(index * 4, value)
}

interface Ptr {
	val addr: Int
	fun sb(offset: Int, value: Int): Unit
	fun sh(offset: Int, value: Int): Unit
	fun sw(offset: Int, value: Int): Unit
	fun lb(offset: Int): Int
	fun lh(offset: Int): Int
	fun lw(offset: Int): Int

	fun sdw(offset: Int, value: Long): Unit {
		sw(offset + 0, (value ushr 0).toInt())
		sw(offset + 4, (value ushr 32).toInt())
	}

	fun ldw(offset: Int): Long {
		val low = lw(offset + 0).unsigned
		val high = lw(offset + 4).unsigned
		return (high shl 32) or low
	}
}

object DummyPtr : Ptr {
	override val addr: Int = 0
	override fun sb(offset: Int, value: Int) = Unit
	override fun sh(offset: Int, value: Int) = Unit
	override fun sw(offset: Int, value: Int) = Unit
	override fun lb(offset: Int): Int = 0
	override fun lh(offset: Int): Int = 0
	override fun lw(offset: Int): Int = 0
}

fun <T> Ptr.read(struct: Struct<T>): T = openSync().read(struct)
fun <T> Ptr.write(struct: Struct<T>, value: T): Unit = openSync().write(struct, value)

inline fun <T, TR> Ptr.capture(struct: Struct<T>, callback: (T) -> TR): TR {
	val ptr = this
	val obj = ptr.openSync().read(struct)
	try {
		return callback(obj)
	} finally {
		ptr.openSync().write(struct, obj)
	}
}

data class MemPtr(val mem: Memory, override val addr: Int) : Ptr {
	override fun sb(offset: Int, value: Int): Unit = mem.sb(addr + offset, value)
	override fun sh(offset: Int, value: Int): Unit = mem.sh(addr + offset, value)
	override fun sw(offset: Int, value: Int): Unit = mem.sw(addr + offset, value)
	override fun lb(offset: Int): Int = mem.lb(addr + offset)
	override fun lh(offset: Int): Int = mem.lh(addr + offset)
	override fun lw(offset: Int): Int = mem.lw(addr + offset)
	override fun toString(): String = "Ptr(0x%08X)".format(addr)
}

fun Ptr.array(size: Int) = PtrArray(this, size)

fun Memory.ptr(addr: Int) = MemPtr(this, addr)

val Ptr.isNotNull: Boolean get() = addr != 0
val Ptr.isNull: Boolean get() = addr == 0

fun Ptr.writeBytes(bytes: ByteArray, offset: Int = 0, size: Int = bytes.size - offset) {
	for (n in 0 until size) this.sb(n, bytes[offset + n].toInt())
}

fun Ptr.readBytes(count: Int, offset: Int = 0): ByteArray {
	val out = ByteArray(count)
	for (n in 0 until count) out[n] = this.lb(offset + n).toByte()
	return out
}

fun Ptr.readStringz(charset: Charset = Charsets.UTF_8): String {
	val out = ByteArrayBuilderSmall()
	var n = 0
	while (true) {
		val c = this.lb(n++)
		if (c == 0) break
		out.append(c.toByte())
		if (out.size >= 0x1000) invalidOp("String is too big!")
	}
	return out.toString(charset)
}

fun Ptr.openSync(): SyncStream {
	return object : SyncStreamBase() {
		override var length: Long = Long.MAX_VALUE
		override fun close() = Unit
		override fun read(position: Long, buffer: ByteArray, offset: Int, len: Int): Int {
			val start = position.toInt()
			for (n in 0 until len) buffer[offset + n] = lb(start + n).toByte()
			return len
		}

		override fun write(position: Long, buffer: ByteArray, offset: Int, len: Int) {
			val start = position.toInt()
			for (n in 0 until len) sb(start + n, buffer[offset + n].toInt())
		}
	}.toSyncStream(0L)
}