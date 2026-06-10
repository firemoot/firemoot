package com.firemoot

import cats.effect.{IO, IOApp}

object Main extends IOApp.Simple:
  def run: IO[Unit] =
    IO.println("Firemoot server - skeleton boot OK")
