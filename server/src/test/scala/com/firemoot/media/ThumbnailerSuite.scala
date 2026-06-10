package com.firemoot.media

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}

import javax.imageio.ImageIO

import cats.effect.IO
import munit.CatsEffectSuite

class ThumbnailerSuite extends CatsEffectSuite:

  private def png(width: Int, height: Int): Array[Byte] =
    val image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.setColor(Color.BLUE)
    g.fillRect(0, 0, width, height)
    g.dispose()
    val out = new ByteArrayOutputStream()
    ImageIO.write(image, "png", out)
    out.toByteArray

  private def dimensions(bytes: Array[Byte]): IO[(Int, Int)] =
    IO.blocking {
      val image = ImageIO.read(new ByteArrayInputStream(bytes))
      (image.getWidth, image.getHeight)
    }

  test("downscales to the max edge, preserving aspect ratio") {
    for
      thumb <- Thumbnailer.resize(png(1000, 400), 512)
      dims <- dimensions(thumb)
    yield assertEquals(dims, (512, 205), "longest edge becomes 512, aspect preserved")
  }

  test("never upscales a small image") {
    for
      thumb <- Thumbnailer.resize(png(100, 80), 512)
      dims <- dimensions(thumb)
    yield assertEquals(dims, (100, 80))
  }

  test("a non-image fails clearly") {
    Thumbnailer.resize("not an image".getBytes, 512).attempt.map { result =>
      assert(result.isLeft, "decoding garbage bytes fails")
    }
  }
