package com.soywiz.kpspemu.format

import KpspTests
import com.soywiz.korio.async.*
import com.soywiz.korio.stream.*
import com.soywiz.kpspemu.format.elf.*
import org.junit.Test
import kotlin.test.*

class PbpTest {
    @Test
    fun name() = syncTest {
        val pbp = Pbp.load(KpspTests.rootTestResources["lines.pbp"].open())
        assertEquals(listOf(408L, 0L, 0L, 0L, 0L, 0L, 30280L, 0L), pbp.streams.map { it.size() })
        assertEquals(408, pbp[Pbp.PARAM_SFO]!!.readAll().size)
        assertEquals(408, pbp[Pbp.PARAM_SFO]!!.readAll().size, "read twice")
        assertEquals(30280, pbp[Pbp.PSP_DATA]!!.readAll().size)
        val elf = Elf.fromStream(pbp[Pbp.PSP_DATA]!!.readAll().openSync())
    }
}