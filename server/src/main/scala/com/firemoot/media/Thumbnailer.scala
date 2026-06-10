package com.firemoot.media

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}

import javax.imageio.ImageIO

import cats.effect.IO

/**
 * Downscales an image to fit within a `maxEdge` x `maxEdge` box, preserving
 * aspect ratio (never upscales), and re-encodes it as PNG (M2.3). ImageIO plus
 * the TwelveMonkeys plugins on the classpath cover the common input formats.
 */
object Thumbnailer:

  def resize(bytes: Array[Byte], maxEdge: Int): IO[Array[Byte]] =
    IO.blocking {
      val source = Option(ImageIO.read(new ByteArrayInputStream(bytes)))
        .getOrElse(throw new IllegalArgumentException("unsupported or unreadable image"))
      val scale = math.min(1.0, maxEdge.toDouble / math.max(source.getWidth, source.getHeight))
      val width = math.max(1, math.round(source.getWidth * scale).toInt)
      val height = math.max(1, math.round(source.getHeight * scale).toInt)

      val target = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
      val g = target.createGraphics()
      try
        g.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION,
          RenderingHints.VALUE_INTERPOLATION_BILINEAR,
        )
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(source, 0, 0, width, height, null)
      finally g.dispose()

      val out = new ByteArrayOutputStream()
      ImageIO.write(target, "png", out)
      out.toByteArray
    }
