package com.google.zxing.qrcode

import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import java.io.toNSData
import kotlinx.cinterop.*
import platform.CoreGraphics.*
import platform.CoreImage.*
import platform.Foundation.*

// iOS 版二维码：用系统 CoreImage 生成，再按 ZXing 的方式（4 格留白、整数倍放大、居中）铺到指定尺寸
class QRCodeWriter {
    fun encode(text: String, @Suppress("UNUSED_PARAMETER") f: BarcodeFormat, w: Int, h: Int): BitMatrix {
        val filter = CIFilter.filterWithName("CIQRCodeGenerator")!!
        filter.setDefaults()
        filter.setValue(text.encodeToByteArray().toNSData(), forKey = "inputMessage")
        filter.setValue("L", forKey = "inputCorrectionLevel")
        val img = filter.outputImage!!
        // CoreImage 自带 1 格留白
        val n = img.extent.useContents { size.width.toInt() }
        val ctx = CIContext.contextWithOptions(null)
        val cg = ctx.createCGImage(img, fromRect = img.extent)!!
        val px = UByteArray(n * n * 4)
        px.usePinned { p ->
            val cs = CGColorSpaceCreateDeviceRGB()
            val bc = CGBitmapContextCreate(p.addressOf(0), n.toULong(), n.toULong(), 8u, (n * 4).toULong(), cs, CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value)
            CGContextDrawImage(bc, CGRectMake(0.0, 0.0, n.toDouble(), n.toDouble()), cg)
            CGContextRelease(bc); CGColorSpaceRelease(cs)
        }
        CGImageRelease(cg)
        val modules = n - 2
        val quiet = 4
        val full = modules + quiet * 2
        val outW = maxOf(w, full); val outH = maxOf(h, full)
        val mult = minOf(outW / full, outH / full)
        val left = (outW - modules * mult) / 2; val top = (outH - modules * mult) / 2
        val m = BitMatrix(outW, outH)
        for (y in 0 until modules) for (x in 0 until modules) {
            // 位图第 0 行在最上面；像素暗 = 黑模块
            val i = ((y + 1) * n + (x + 1)) * 4
            if (px[i].toInt() < 128) for (dy in 0 until mult) for (dx in 0 until mult) m.set(left + x * mult + dx, top + y * mult + dy)
        }
        return m
    }
}
