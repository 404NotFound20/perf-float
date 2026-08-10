package com.notfound.perffloat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProcParserTest {

    @Test
    fun parseCpuTimes_handlesStandardFormat() {
        val line = "cpu  1000 200 300 4000 500 60 70 80 0 0"
        val times = ProcParser.parseCpuTimes(line)
        assertNotNull(times)
        // idle = 4000 + 500 = 4500; total = 前 8 个字段 = 1000+200+300+4000+500+60+70+80 = 6210
        assertEquals(6210L, times!!.totalJiffies)
        assertEquals(4500L, times.idleJiffies)
    }

    @Test
    fun parseCpuTimes_ignoresPerCoreLines() {
        assertNull(ProcParser.parseCpuTimes("cpu0 100 200 300 400 0 0 0 0"))
    }

    @Test
    fun parseCpuTimes_returnsNullOnMalformed() {
        assertNull(ProcParser.parseCpuTimes("not a cpu line"))
        assertNull(ProcParser.parseCpuTimes("cpu 1 2"))
    }

    @Test
    fun cpuLoadPercent_50PercentBusy() {
        // 第二次采样：idle 增加 100，total 增加 200 → 使用率 50%
        val prev = ProcParser.CpuTimes(totalJiffies = 1000, idleJiffies = 700)
        val curr = ProcParser.CpuTimes(totalJiffies = 1200, idleJiffies = 800)
        assertEquals(50f, ProcParser.cpuLoadPercent(prev, curr), 0.01f)
    }

    @Test
    fun cpuLoadPercent_clampsToZeroWhenNoProgress() {
        val prev = ProcParser.CpuTimes(totalJiffies = 1000, idleJiffies = 700)
        val curr = ProcParser.CpuTimes(totalJiffies = 1000, idleJiffies = 700)
        assertEquals(0f, ProcParser.cpuLoadPercent(prev, curr), 0.01f)
    }

    @Test
    fun cpuLoadPercent_fullBusy() {
        val prev = ProcParser.CpuTimes(totalJiffies = 1000, idleJiffies = 700)
        val curr = ProcParser.CpuTimes(totalJiffies = 1100, idleJiffies = 700)
        assertEquals(100f, ProcParser.cpuLoadPercent(prev, curr), 0.01f)
    }

    @Test
    fun parsePerCoreCpuTimes_sortsByCoreIndex() {
        val content = """
            cpu  1000 200 300 4000 500 60 70 80 0 0
            cpu0 100 20 30 400 50 0 0 0 0 0
            cpu1 200 20 30 800 50 0 0 0 0 0
            cpu2 300 20 30 1200 50 0 0 0 0 0
        """.trimIndent()
        val cores = ProcParser.parsePerCoreCpuTimes(content)
        assertEquals(3, cores.size)
        assertEquals(450L, cores[0].idleJiffies) // cpu0: 400 + 50
        assertEquals(850L, cores[1].idleJiffies) // cpu1: 800 + 50
        assertEquals(1250L, cores[2].idleJiffies)
    }

    @Test
    fun parsePerCoreCpuTimes_ignoresTotalLine() {
        val cores = ProcParser.parsePerCoreCpuTimes("cpu  100 200 300 400 0 0 0 0")
        assertEquals(0, cores.size)
    }

    @Test
    fun perCoreLoadComputedWithDelta() {
        val prevContent = "cpu0 100 20 30 400 50 0 0 0 0 0"
        val currContent = "cpu0 150 20 30 450 50 0 0 0 0 0"
        val prev = ProcParser.parsePerCoreCpuTimes(prevContent)[0]
        val curr = ProcParser.parsePerCoreCpuTimes(currContent)[0]
        // total 增加 100（cpu0: 150-100=50，450-400=50），idle 增加 50 → busy 50/100 = 50%
        assertEquals(50f, ProcParser.cpuLoadPercent(prev, curr), 0.01f)
    }

    @Test
    fun parseMemInfo_extractsTotalAndAvailable() {
        val content = """
            MemTotal:       8388608 kB
            MemFree:         1048576 kB
            MemAvailable:    3145728 kB
            Buffers:           65536 kB
        """.trimIndent()
        val (totalKb, availableKb) = ProcParser.parseMemInfo(content)!!
        assertEquals(8_388_608L, totalKb)
        assertEquals(3_145_728L, availableKb)
    }

    @Test
    fun parseMemInfo_returnsNullWhenUnavailable() {
        assertNull(ProcParser.parseMemInfo("MemTotal: 1024 kB"))
    }

    @Test
    fun parseThermalTemp_convertsMilliToCelsius() {
        assertEquals(38f, ProcParser.parseThermalTemp("38000")!!, 0.01f)
        assertEquals(45.5f, ProcParser.parseThermalTemp("45500")!!, 0.01f)
    }

    @Test
    fun parseThermalTemp_ignoresSentinelAndGarbage() {
        assertNull(ProcParser.parseThermalTemp("-127000"))
        assertNull(ProcParser.parseThermalTemp("unknown"))
    }

    @Test
    fun parseLoadAvg_extractsOneMinuteLoad() {
        assertEquals(0.52f, ProcParser.parseLoadAvg("0.52 0.38 0.20 1/345 1234")!!, 0.001f)
        assertNull(ProcParser.parseLoadAvg(""))
    }
}
