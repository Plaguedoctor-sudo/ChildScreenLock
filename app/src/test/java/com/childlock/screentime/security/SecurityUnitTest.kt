package com.childlock.screentime.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class SecurityUnitTest {

    @Test
    fun testKatSelfTests() {
        val passed = KnownAnswerTest.runPowerOnSelfTests()
        assertTrue("Known answer tests must pass", passed)
    }

    @Test
    fun testCosineSimilarityIdentical() {
        val vec1 = floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f)
        val vec2 = floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f)
        var dot = 0f
        var norm1 = 0f
        var norm2 = 0f
        for (i in vec1.indices) {
            dot += vec1[i] * vec2[i]
            norm1 += vec1[i] * vec1[i]
            norm2 += vec2[i] * vec2[i]
        }
        val sim = dot / (sqrt(norm1) * sqrt(norm2))
        assertEquals(1.0f, sim, 0.0001f)
    }

    @Test
    fun testCosineSimilarityOrthogonal() {
        val vec1 = floatArrayOf(1f, 0f, 0f, 0f)
        val vec2 = floatArrayOf(0f, 1f, 0f, 0f)
        var dot = 0f
        for (i in vec1.indices) {
            dot += vec1[i] * vec2[i]
        }
        assertEquals(0.0f, dot, 0.0001f)
    }
}
