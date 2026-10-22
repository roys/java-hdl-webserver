package com.roysolberg.java.hdlserver

import io.nayuki.qrcodegen.QrCode
import java.time.Instant

/**
 * The open pairing window as Settings shows it: the link as a QR code to scan, as a link that opens the app on the phone
 * itself, and as text to copy.
 */
class PairingView(val link: String, val intentLink: String, val closesAt: Instant) {

    /** The QR code as inline SVG, dark on light whatever the page's colours, so it scans. Only numbers come from outside. */
    val qrSvg: String = svg(QrCode.encodeText(link, QrCode.Ecc.MEDIUM))

    private companion object {
        /** The quiet zone scanners need around the code, in modules. */
        const val BORDER = 4

        fun svg(qr: QrCode): String {
            val size = qr.size + BORDER * 2
            val path = buildString {
                for (y in 0 until qr.size) {
                    for (x in 0 until qr.size) {
                        if (qr.getModule(x, y)) append("M${x + BORDER},${y + BORDER}h1v1h-1z")
                    }
                }
            }
            return """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $size $size" class="qr-code" role="img" """ +
                """aria-label="QR code of the pairing link" shape-rendering="crispEdges">""" +
                """<rect width="$size" height="$size" fill="#fff"/><path d="$path" fill="#000"/></svg>"""
        }
    }
}
