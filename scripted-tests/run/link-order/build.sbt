enablePlugins(ScalaNativePlugin)

scalaVersion := "2.11.12"

nativeLinkingOptions in Compile += s"-L${target.value.getAbsoluteFile}"

compile in Compile := {
  val log            = streams.value.log
  val cwd            = target.value
  val compileOptions = nativeCompileOptions.value
  val cpaths         = (baseDirectory.value.getAbsoluteFile * "*.c").get
  val clangPath      = nativeClang.value.toPath.toAbsolutePath.toString

  cwd.mkdirs()

  def abs(path: File): String =
    path.getAbsolutePath

  def run(command: Seq[String]): Int = {
    log.info("Running " + command.mkString(" "))
    Process(command, cwd) ! log
  }

  val opaths = cpaths.map { cpath =>
    val opath = abs(cwd / s"${cpath.getName}.o")
    val command = Seq(clangPath) ++ compileOptions ++
      Seq("-c", abs(cpath), "-o", opath)

    if (run(command) != 0) {
      sys.error(s"Failed to compile $cpath")
    }
    opath
  }

  val archivePath = cwd / "liblink-order-test.a"
  val archive     = Seq("ar", "cr", abs(archivePath)) ++ opaths
  if (run(archive) != 0) {
    sys.error(s"Failed to create archive $archivePath")
  }

  (compile in Compile).value
}
