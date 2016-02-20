package injector

import scala.language.experimental.macros

/**
  * @author Aleksey Fomkin <aleksey.fomkin@gmail.com>
  */
trait Injector {

  def accessors: Map[String, () ⇒ Any]

  def apply[T]: T = macro InjectorMacro.resolve[T]
}
