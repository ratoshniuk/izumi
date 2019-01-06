package com.github.pshirshov.izumi.idealingua.compiler

import java.nio.file._

import com.github.pshirshov.izumi.fundamentals.platform.files.IzFiles
import com.github.pshirshov.izumi.fundamentals.platform.language.Quirks._
import com.github.pshirshov.izumi.fundamentals.platform.resources.{IzManifest, IzResources}
import com.github.pshirshov.izumi.fundamentals.platform.strings.IzString._
import com.github.pshirshov.izumi.fundamentals.platform.time.Timed
import com.github.pshirshov.izumi.idealingua.compiler.Codecs._
import com.github.pshirshov.izumi.idealingua.il.loader.{LocalModelLoaderContext, ModelResolver}
import com.github.pshirshov.izumi.idealingua.model.loader.UnresolvedDomains
import com.github.pshirshov.izumi.idealingua.model.publishing.{BuildManifest, ProjectVersion}
import com.github.pshirshov.izumi.idealingua.translator._
import com.typesafe.config.ConfigFactory
import io.circe.parser.parse
import io.circe.syntax._
import io.circe.{Json, JsonObject}

import scala.collection.JavaConverters._

object CommandlineIDLCompiler {
  private val log: CompilerLog = CompilerLog.Default
  private val shutdown: Shutdown = ShutdownImpl

  def main(args: Array[String]): Unit = {
    val mf = IzManifest.manifest[CommandlineIDLCompiler.type]().map(IzManifest.read)
    val izumiVersion = mf.map(_.version.toString).getOrElse("0.0.0-UNKNOWN")
    val izumiInfoVersion = mf.map(_.justVersion).getOrElse("UNKNOWN-BUILD")

    log.log(s"Izumi IDL Compiler $izumiInfoVersion")

    val conf = parseArgs(args)

    val results = Seq(
      initDir(conf),
      runCompilations(izumiVersion, conf),
    )

    if (!results.contains(true)) {
      log.log("There was nothing to do. Try to run with `--help`")
    }
  }

  private def initDir(conf: IDLCArgs): Boolean = {
    conf.init match {
      case Some(p) =>
        log.log(s"Initializing layout in `$p` ...")
        val f = p.toFile
        if (f.exists()) {
          if (f.isDirectory) {
            if (f.listFiles().nonEmpty) {
              shutdown.shutdown(s"Exists and not empty: $p")
            }
          } else {
            shutdown.shutdown(s"Exists and not a directory: $p")
          }
        }

        val mfdir = p.resolve("manifests")
        mfdir.toFile.mkdirs().discard()
        IzResources.copyFromClasspath("defs/example", p).discard()

        TypespaceCompilerBaseFacade.descriptors.foreach {
          d =>
            Files.write(mfdir.resolve(s"${d.language.toString.toLowerCase}.json"), new ManifestWriter().write(d.defaultManifest).utf8).discard()
        }

        Files.write(p.resolve(s"version.json"), ProjectVersion.default.asJson.toString().utf8).discard()
        true
      case None =>
        false
    }
  }

  private def runCompilations(izumiVersion: String, conf: IDLCArgs) = {
    if (conf.languages.nonEmpty) {
      log.log("Reading manifests...")
      val toRun = conf.languages.map(toOption(conf, Map("common.izumiVersion" -> izumiVersion)))
      log.log("Going to compile:")
      log.log(toRun.niceList())
      log.log("")

      val path = conf.source.toAbsolutePath
      val target = conf.target.toAbsolutePath
      target.toFile.mkdirs()

      log.log(s"Loading definitions from `$path`...")

      val loaded = Timed {
        if (path.toFile.exists() && path.toFile.isDirectory) {
          val context = new LocalModelLoaderContext(path, Seq.empty)
          context.loader.load()
        } else {
          shutdown.shutdown(s"Not exists or not a directory: $path")
        }
      }
      log.log(s"Done: ${loaded.value.domains.results.size} in ${loaded.duration.toMillis}ms")
      log.log("")

      toRun.foreach {
        option =>
          runCompiler(target, loaded, option)
      }
      true
    } else {
      false
    }

  }

  private def runCompiler(target: Path, loaded: Timed[UnresolvedDomains], option: UntypedCompilerOptions): Unit = {
    val langId = option.language.toString
    val itarget = target.resolve(langId)
    log.log(s"Preparing typespace for $langId")
    val toCompile = Timed {
      val rules = TypespaceCompilerBaseFacade.descriptor(option.language).rules
      new ModelResolver(rules)
        .resolve(loaded.value)
        .ifWarnings {
          message =>
            log.log(message)
        }
        .ifFailed {
          message =>
            shutdown.shutdown(message)
        }
        .successful
    }
    log.log(s"Finished in ${toCompile.duration.toMillis}ms")

    val out = Timed {
      new TypespaceCompilerFSFacade(toCompile)
        .compile(itarget, option)
    }

    val allPaths = out.compilationProducts.paths

    log.log(s"${allPaths.size} source files from ${toCompile.size} domains produced in `$itarget` in ${out.duration.toMillis}ms")
    log.log(s"Archive: ${out.zippedOutput}")
    log.log("")
  }

  private def parseArgs(args: Array[String]) = {
    val default = IDLCArgs.default
    val conf = IDLCArgs.parser.parse(args, default) match {
      case Some(c) =>
        c
      case _ =>
        IDLCArgs.parser.showUsage()
        throw new IllegalArgumentException("Unexpected commandline")
    }
    conf
  }

  private def toOption(conf: IDLCArgs, env: Map[String, String])(lopt: LanguageOpts): UntypedCompilerOptions = {
    val lang = IDLLanguage.parse(lopt.id)
    val exts = getExt(lang, lopt.extensions)

    val manifest = readManifest(conf, env, lopt, lang)
    UntypedCompilerOptions(lang, exts, manifest, lopt.withRuntime)
  }

  private def readManifest(conf: IDLCArgs, env: Map[String, String], lopt: LanguageOpts, lang: IDLLanguage): BuildManifest = {
    val default = Paths.get("version.json")

    val overlay = conf.versionOverlay.map(loadVersionOverlay) match {
      case Some(value) =>
        Some(value)
      case None if default.toFile.exists() =>
        log.log(s"Found $default, using as version overlay...")
        Some(loadVersionOverlay(default))
      case None =>
        None
    }

    val overlayJson = overlay match {
      case Some(Right(value)) =>
        value
      case Some(Left(e)) =>
        shutdown.shutdown(s"Failed to parse version overlay: ${e.getMessage()}")
      case None =>
        JsonObject.empty.asJson
    }

    val envJson = toJson(env)
    val languageOverridesJson = toJson(lopt.overrides)
    val globalOverridesJson = toJson(conf.overrides)
    val patch = overlayJson.deepMerge(globalOverridesJson).deepMerge(envJson).deepMerge(languageOverridesJson)

    val reader = new ManifestReader(log, shutdown, patch, lang, lopt.manifest)
    val manifest = reader.read()
    manifest
  }

  private def loadVersionOverlay(path: Path) = {
    import io.circe.literal._
    parse(IzFiles.readString(path.toFile)).map(vj => json"""{"common": {"version": $vj}}""")
  }

  private def toJson(env: Map[String, String]) = {
    valToJson(ConfigFactory.parseMap(env.asJava).root().unwrapped())
  }

  private def valToJson(v: AnyRef): Json = {
    import io.circe.syntax._

    v match {
      case m: java.util.HashMap[_, _] =>
        m.asScala
          .map {
            case (k, value) =>
              k.toString -> valToJson(value.asInstanceOf[AnyRef])
          }
          .asJson

      case s: String =>
        s.asJson

    }
  }


  private def getExt(lang: IDLLanguage, filter: List[String]): Seq[TranslatorExtension] = {
    val descriptor = TypespaceCompilerBaseFacade.descriptor(lang)
    val negative = filter.filter(_.startsWith("-")).map(_.substring(1)).map(ExtensionId).toSet
    descriptor.defaultExtensions.filterNot(e => negative.contains(e.id))
  }
}



