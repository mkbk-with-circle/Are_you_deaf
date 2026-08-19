package com.nierduolong.morningbell.dailylog.lan

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyInviteTest {
    @Test
    fun roundTripsUnicodeHotspotAndRoomData() {
        val invite =
            NearbyInvite(
                remoteLogId = "room-identity-2026",
                inviteCode = "038271",
                logName = "北大朋友的 Log",
                ssid = "DIRECT-热点 A&B",
                passphrase = "p+a&s?word",
            )

        assertEquals(invite, NearbyInvite.parse(invite.encode()))
        assertTrue(invite.encode().startsWith("nierlog://join?v=1"))
    }

    @Test
    fun rejectsWrongSchemeVersionAndUnsafeCodes() {
        assertNull(NearbyInvite.parse("https://example.com/join?v=1&id=room-12345&code=123456"))
        assertNull(NearbyInvite.parse("nierlog://join?v=2&id=room-12345&code=123456"))
        assertNull(NearbyInvite.parse("nierlog://join?v=1&id=room-12345&code=12A456"))
        assertNull(NearbyInvite.parse("nierlog://join?v=1&id=short&code=123456"))
    }

    @Test
    fun generatedQrCanBeDecodedBackIntoTheInvite() {
        val invite = NearbyInvite("room-qr-roundtrip", "654321", "周末 Log", "Hotspot", "password")
        val matrix = QRCodeWriter().encode(invite.encode(), BarcodeFormat.QR_CODE, 320, 320)
        val pixels = IntArray(320 * 320) { index -> if (matrix[index % 320, index / 320]) 0xff000000.toInt() else 0xffffffff.toInt() }
        val decoded =
            MultiFormatReader().decode(
                BinaryBitmap(HybridBinarizer(RGBLuminanceSource(320, 320, pixels))),
            ).text

        assertEquals(invite, NearbyInvite.parse(decoded))
    }
}
