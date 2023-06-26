package com.carlosedp.aliases

import mill._
import mill.define.ExternalModule
import mill.eval.Evaluator

import Discover._
import AliasRunner._
import mill.api.Result.Success
import mill.api.Result.Failure
import mill.api.Result.Aborted
import mill.api.Result
import mill.api.Result.Skipped

/**
 * Aliases module
 *
 * This trait defines the aliases existing in the project
 */
trait Aliases extends Module {
  type alias = Seq[String]
  def alias(xs: String*) = Seq(xs: _*)
}

/**
 * Alias object
 *
 * @param name
 *   Alias name
 * @param module
 *   Module which contains the alias
 * @param tasks
 *   Tasks to be executed
 */
private case class Alias(
  name:   String,
  module: Module,
  tasks:  Seq[String],
)

/**
 * Aliases module
 *
 * This module is used to list and run aliases
 */
object AliasesModule extends ExternalModule {

  lazy val millDiscover: mill.define.Discover[this.type] =
    mill.define.Discover[this.type]

  /**
   * List all aliases
   *
   * @param ev
   * @return
   */
  def list(ev: Evaluator) = T.command {
    Console.err.println("Use './mill run [alias]'.");
    Console.out.println("Available aliases:")

    // Print all aliases to the console
    Console.out.println(
      "┌─────────────────┬─────────────────┬───────────────────────────────────────────────────────────────────────────────────"
    )
    Console.out.println("| Alias           | Module          | Command(s) ")
    Console.out.println(
      "├─────────────────┼─────────────────┼───────────────────────────────────────────────────────────────────────────────────"
    )

    getAllAliases(ev).foreach(x =>
      Console.out.println(
        s"| ${x.name.padTo(15, ' ')} | ${x.module.toString.padTo(15, ' ')} | (${x.tasks.mkString(", ")})"
      )
    )
    Console.out.println(
      "└─────────────────┴─────────────────┴───────────────────────────────────────────────────────────────────────────────────"
    )
    Console.out.println("")

  }

  /**
   * Run an alias
   *
   * @param ev
   *   Evaluator
   * @param alias
   *   Alias name
   * @return
   */
  def run(ev: Evaluator, aliasName: String) = T.command {
    findAliasByName(aliasName, getAllAliases(ev)) match {
      case None =>
        Console.err.println(s"Alias '$aliasName' not found.")
        Console.err.println(s"")
        printHelp()
        sys.exit(1)
      case Some(alias) =>
        val runTasks = alias.tasks.flatMap(x => Seq(x, "+")).flatMap(_.split("\\s+")).init
        checkAliasTasks(ev, alias) match {
          case Aborted | Result.Exception(_, _) | Failure(_, _) | Skipped =>
            Console.err.println(
              s"Error: The task defined in alias '${alias.name}' is invalid: (${alias.tasks.mkString(", ")})"
            )
            sys.exit(1)
          case Success(value) => value
        }
        Console.out.println(s"Running alias $aliasName")
        aliasRunner(
          ev,
          runTasks,
        )
    }
  }

  def help() = T.command {
    Console.err.println("--------------------")
    Console.err.println("Mill Aliases Plugin")
    Console.err.println("--------------------")
    printHelp()
  }

  // --------------------------------------------------------------------------------------------------------------------

  /**
   * Prints help message to the console
   */
  private def printHelp() = {
    Console.err.println("The plugin allows you to define aliases for mill tasks.")
    Console.err.println(
      "The aliases are defined in an object extending `Aliases` in the build.sc file at the root level in the following format:"
    )
    Console.err.println("""
                          |  object MyAliases extends Aliases {
                          |    def testall     = alias("__.test")
                          |    def compileall  = alias("__.compile")
                          |    def testcompall = alias("__.compile", "__.test")
                          |  }
    """.stripMargin)
    Console.err.println("Aliases can be defines for one or multiple tasks which will be run in sequence.")
    Console.err.println("The aliases can be run with './mill Alias/run [alias]'.")
    Console.err.println("To list all aliases: './mill Alias/list'")
  }

  /**
   * Get all modules that extend [[Aliases]] in the project
   *
   * @param ev
   *   Evaluator
   * @return
   *   A `Seq` of [[Aliases]] modules
   */
  private def aliasModules(ev: Evaluator): Seq[Module with Aliases] =
    ev.rootModule.millInternal.modules.collect { case m: Aliases => m }

  /**
   * Get all aliases in the project module
   *
   * @param module
   * @return
   *   A `Seq` of aliases
   */
  private def aliasMethods(module: Module): Seq[String] =
    module
      .getClass()
      .getMethods()
      .map(_.getName())
      .filterNot(
        Seq(
          "alias",
          "wait",
          "equals",
          "toString",
          "hashCode",
          "getClass",
          "notify",
          "notifyAll",
          "mill$define$Module$$millModuleDirectChildrenImpl",
          "millModuleDirectChildren",
          "millModuleExternal",
          "millModuleShared",
          "millInternal",
          "millModuleSegments",
          "millSourcePath",
          "millModuleBasePath",
          "mill$moduledefs$Cacher$$cacherLazyMap",
          "millOuterCtx",
          "cachedTarget",
        ).contains(_)
      ).toSeq

  /**
   * Get all commands for an alias in a specific module
   *
   * @param module
   * @param alias
   * @return
   *   A `Seq` of tasks
   */
  private def aliasCommands(module: Module, alias: String): Seq[String] =
    module.getClass().getDeclaredMethod(alias).invoke(module).asInstanceOf[Seq[String]]

  /**
   * Get all aliases in the project
   *
   * @param ev
   *   Evaluator
   * @return
   *   A `Seq` of [[Aliases]]
   */
  private def getAllAliases(ev: Evaluator): Seq[Alias] =
    aliasModules(ev).flatMap { module =>
      aliasMethods(module).map { alias =>
        Alias(
          name = alias,
          module = module,
          tasks = aliasCommands(module, alias),
        )
      }
    }

  /**
   * Find an alias by name
   *
   * @param name
   *   Alias name
   * @param aliases
   *   A `Seq` of `[[Alias]]`es
   * @return
   */
  private def findAliasByName(name: String, aliases: Seq[Alias]): Option[Alias] =
    aliases.find(_.name == name)

  /**
   * Check if any task in alias is invalid
   *
   * @param ev
   *   Evaluator
   * @param alias
   *   Alias to check
   * @return
   */
  private def checkAliasTasks(ev: Evaluator, alias: Alias) = {
    val filteredTasks = alias.tasks.flatMap(_.split("\\s+"))
    Utils.taskResolver(ev, filteredTasks)
  }
}
