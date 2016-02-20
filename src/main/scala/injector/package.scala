import scala.language.experimental.macros

package object injector {

  private case object compileTimeConfiguration extends Config

  /**
    * @example {{{
    * import injector.Injector._
    *
    * val injector = configure("client")(
    *   singleton[UserModel].use[UserModelImpl],
    *   prototype[Goods]
    * )
    * }}}
    */
  def configure(name: String)(xs: Config*): Injector = macro InjectorMacro.configure

  def configure(xs: Config*): Injector = macro InjectorMacro.configureDefault

  def singleton[T]: Config = compileTimeConfiguration

  def instance[T](value: T): Config = compileTimeConfiguration

  def lazySingleton[T]: Config = compileTimeConfiguration

  def prototype[T]: Config = compileTimeConfiguration

  sealed trait Config {
    def use[T]: Config = compileTimeConfiguration
  }

}
