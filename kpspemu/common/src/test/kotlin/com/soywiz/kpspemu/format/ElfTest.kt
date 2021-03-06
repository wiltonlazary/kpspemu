package com.soywiz.kpspemu.format

import com.soywiz.korio.async.*
import com.soywiz.korio.stream.*
import com.soywiz.kpspemu.*
import com.soywiz.kpspemu.embedded.*
import com.soywiz.kpspemu.format.elf.*
import org.junit.Test
import kotlin.test.*

class ElfTest {
    @Test
    fun name() = syncTest {
        val emulator = Emulator(coroutineContext)
        val elf = emulator.loadElf(Samples.MINIFIRE_ELF.openSync())
        assertEquals(0x08900008, elf.moduleInfo.PC)
        assertEquals(0x00004821, elf.moduleInfo.GP)
    }
}