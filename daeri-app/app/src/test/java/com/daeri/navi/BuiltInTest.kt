package com.daeri.navi

import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInTest {
    @Test fun 내장_키_형식() {
        assertTrue(Regex("^[0-9a-f]{32}$").matches(BuiltIn.rest))
        assertTrue(Regex("^[0-9a-f]{32}$").matches(BuiltIn.js))
    }
}
