package com.movieshell.data.source

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.3.33 B-P2-3：PY“无推荐”计数代次去重（纯逻辑，不调规则、不碰时钟）。
 */
class PyApiNoRecommendTest {

    private class StubEngine : PyEngine {
        override suspend fun invoke(rulePath: String, method: String, argsJson: String): String = ""
        override suspend fun bookInvoke(rulePath: String, method: String, argsJson: String): String = ""
        override suspend fun resetRuleCache(rulePath: String) {}
    }

    @Test
    fun countNoRecommend_sameGenerationCountsOnce() {
        val api = PyApi(StubEngine())
        assertEquals(1, api.countNoRecommend("r.py", 1L, false))
        assertEquals(1, api.countNoRecommend("r.py", 1L, false))
        assertEquals(1, api.countNoRecommend("r.py", 1L, false))
    }

    @Test
    fun countNoRecommend_newGenerationCountsAgain() {
        val api = PyApi(StubEngine())
        assertEquals(1, api.countNoRecommend("r.py", 1L, false))
        assertEquals(1, api.countNoRecommend("r.py", 1L, false))
        assertEquals(2, api.countNoRecommend("r.py", 2L, false))
        assertEquals(3, api.countNoRecommend("r.py", 3L, false))
    }

    @Test
    fun countNoRecommend_rulesAreIndependent() {
        val api = PyApi(StubEngine())
        assertEquals(1, api.countNoRecommend("a.py", 1L, false))
        assertEquals(1, api.countNoRecommend("b.py", 1L, false))
        assertEquals(2, api.countNoRecommend("a.py", 2L, false))
        assertEquals(1, api.countNoRecommend("b.py", 1L, false))
    }

    @Test
    fun countNoRecommend_hasRecommendClears() {
        val api = PyApi(StubEngine())
        assertEquals(1, api.countNoRecommend("r.py", 1L, false))
        assertEquals(0, api.countNoRecommend("r.py", 1L, true))
        assertEquals(1, api.countNoRecommend("r.py", 2L, false))
    }
}
