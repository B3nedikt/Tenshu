package org.tenshu.core.common.util

import kotlin.test.Test
import kotlin.test.assertEquals

class Md5HashTest {

    @Test
    fun testEmptyString() {
        val input = "".encodeToByteArray()
        val expected = "d41d8cd98f00b204e9800998ecf8427e"
        val actual = md5Hash(input).toHexString()
        assertEquals(expected, actual, "MD5 of empty string")
    }

    @Test
    fun testABC() {
        val input = "abc".encodeToByteArray()
        val expected = "900150983cd24fb0d6963f7d28e17f72"
        val actual = md5Hash(input).toHexString()
        assertEquals(expected, actual, "MD5 of 'abc'")
    }

    @Test
    fun testMessageDigest() {
        val input = "message digest".encodeToByteArray()
        val expected = "f96b697d7cb7938d525a2f31aaf161d0"
        val actual = md5Hash(input).toHexString()
        assertEquals(expected, actual, "MD5 of 'message digest'")
    }

    @Test
    fun testAlphabet() {

        val hash = md5Hash("abc".encodeToByteArray())
        val hex = hash.toHexString()
        println(hex) // Output: 900150983cd24fb0d6963f7d28e17f72


        val input = "abcdefghijklmnopqrstuvwxyz".encodeToByteArray()
        val expected = "c3fcd3d76192e4007dfb496cca67e13b"
        val actual = md5Hash(input).toHexString()
        assertEquals(expected, actual, "MD5 of alphabet")
    }

    @Test
    fun testAlphanumeric() {
        val input =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".encodeToByteArray()
        val expected = "d174ab98d277d9f5a5611c2c9f419d9f"
        val actual = md5Hash(input).toHexString()
        assertEquals(expected, actual, "MD5 of alphanumeric")
    }

    @Test
    fun testRepeatedA() {
        val input = "a".repeat(1000).encodeToByteArray()
        val expected = "cabe45dcc9ae5b66ba86600cca6b8ba8"
        val actual = md5Hash(input).toHexString()
        assertEquals(expected, actual, "MD5 of 1000 'a's")
    }
}


