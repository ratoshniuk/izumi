package com.github.pshirshov.izumi.fundamentals.platform.cli

import org.scalatest.WordSpec

class CliParserTest extends WordSpec {

  "CLI parser" should {
    "parse args" in {
      val v1 = RoleAppArguments(Parameters(Vector(Flag("help")), Vector(Value("x", "y"), Value("logs", "json"))), Vector(RoleArg("role1", Parameters(Vector.empty, Vector(Value("config", "xxx"))), Vector("arg1", "arg2")), RoleArg("role2", Parameters.empty, Vector.empty)))
      val v2= RoleAppArguments(Parameters(Vector(Flag("help")),Vector(Value("x","y"), Value("logs","json"))),Vector(RoleArg("role1",Parameters(Vector.empty,Vector(Value("config","xxx"))),Vector("arg1", "arg2", "--yyy=zzz")), RoleArg("role2",Parameters.empty,Vector.empty)))
      val v3 = RoleAppArguments(Parameters(Vector(Flag("help")),Vector(Value("x","y"), Value("logs","json"))),Vector(RoleArg("role1",Parameters(Vector.empty,Vector.empty), Vector("--config=xxx", "arg1", "arg2", "--yyy=zzz")), RoleArg("role2",Parameters.empty,Vector.empty)))
      val v4 = RoleAppArguments(Parameters(Vector(Flag("x"), Flag("x")),Vector(Value("x","y"))),Vector(RoleArg("role1",Parameters(Vector(Flag("x"), Flag("x")),Vector(Value("x","y"), Value("xx","yy"))),Vector.empty)))
      assert(new CLIParser().parse(Array("--help", "--x=y", "--logs=json", ":role1", "--config=xxx", "arg1", "arg2", ":role2")) == Right(v1))
      assert(new CLIParser().parse(Array("--help", "--x=y", "--logs=json", ":role1", "--config=xxx", "arg1", "arg2", "--yyy=zzz", ":role2")) == Right(v2))
      assert(new CLIParser().parse(Array("--help", "--x=y", "--logs=json", ":role1", "--", "--config=xxx", "arg1", "arg2", "--yyy=zzz", ":role2")) == Right(v3))
      assert(new CLIParser().parse(Array("-x", "-x", "y", "-x", ":role1", "-x", "-x", "y", "-x", "--xx=yy")) == Right(v4))

      assert(new CLIParser().parse(Array("-x")).exists(_.globalParameters.flags.head.name == "x"))
      assert(new CLIParser().parse(Array("-x", "value")).exists(_.globalParameters.values.head == Value("x", "value")))
      assert(new CLIParser().parse(Array("--x", "value")).isLeft)
      assert(new CLIParser().parse(Array("--x=value")).exists(_.globalParameters.values.head == Value("x", "value")))
      assert(new CLIParser().parse(Array(":init", "./tmp")) == Right(RoleAppArguments(Parameters.empty,Vector(RoleArg("init",Parameters.empty,Vector("./tmp"))))))
    }
  }


}
