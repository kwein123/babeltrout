package com.kevin.babeltrout

import org.junit.Assert.assertEquals
import org.junit.Test

class PiperSpeakerTest {

    /**
     * sherpa-onnx's native code calls the audio callback by JNI signature invoke([F)Ljava/lang/Integer;.
     * Replacing StreamingSink with a lambda compiles fine but crashes release builds, so pin it here.
     */
    @Test
    fun `audio callback exposes the JNI signature sherpa-onnx calls`() {
        val method = PiperSpeaker.StreamingSink::class.java.getMethod("invoke", FloatArray::class.java)
        assertEquals(Integer::class.java, method.returnType)
    }

    @Test
    fun `proguard keeps the callback`() {
        val rules = java.io.File("proguard-rules.pro").readText()
        assert(rules.contains("PiperSpeaker\$StreamingSink")) { "keep rule for StreamingSink missing" }
        assert(rules.contains("java.lang.Integer invoke(float[]);"))
    }
}
