package injector

import java.io.{File, PrintWriter}

import scala.annotation.tailrec
import scala.reflect.macros.blackbox

class InjectorMacro[C <: blackbox.Context](val c: C) {
  import c.universe._

  def resolve[T: c.WeakTypeTag]: c.Tree = {
    val self = c.prefix
    val tpe = weakTypeOf[T]
    val name = tpe.typeSymbol.fullName

    q"""
      val fun = $self.accessors($name)
      fun().asInstanceOf[$tpe]
    """
  }

  def configure(name: c.Tree)(xs: Seq[c.Tree]): c.Tree = {
    val configName = name match { case q"${s: String}" ⇒ s }
    configureBase(configName, xs)
  }

  def configureDefault(xs: Seq[c.Tree]): c.Tree = {
    configureBase("default", xs)
  }

  def configureBase(configName: String, xs: Seq[c.Tree]): c.Tree = {
    // Wrap types with CT cause `c.Type`
    // hasn't equals and hashcode functions
    // that are needs to make diff.
    case class CT(tpe: c.Type) {
      def name = tpe.typeSymbol.name.toString
      def fullName = tpe.typeSymbol.fullName
      override def hashCode(): Int = fullName.hashCode
      override def equals(obj: scala.Any): Boolean = obj match {
        case ct: CT ⇒ ct.fullName == fullName
        case _ ⇒ super.equals(obj)
      }
    }

    sealed trait Node {
      def n: Int
      def ct: CT
    }

    sealed trait NodeWithDependencies extends Node {
      def dependencies: List[CT]
    }

    case class Singleton(
      n: Int,
      ct: CT,
      dependencies: List[CT],
      impl: Option[CT],
      lzy: Boolean) extends NodeWithDependencies

    case class Prototype(
      n: Int,
      ct: CT,
      dependencies: List[CT],
      impl: Option[CT]) extends NodeWithDependencies

    case class Instance(
      n: Int,
      ct: CT,
      value: c.Tree
    ) extends Node

    def checkIsNotAbstract(t: c.Type, pos: c.Position): Unit = {
      if (t.typeSymbol.isAbstract) {
        c.abort(pos, s"`$t` shouldn't be abstract")
      }
    }

    def checkIsSuper(trt: c.Type, cls: c.Type, pos: c.Position): Unit = {
      if (!cls.baseClasses.contains(trt.typeSymbol)) {
        c.abort(pos, s"`$cls` should inherit `$trt`")
      }
    }

    // Take type of all arguments of constructor of `t`
    def extractDependencies(t: c.Type): List[c.Type] = {
      t.decls.toList flatMap {
        case m: MethodSymbol if m.isConstructor ⇒
          m.paramLists.flatten.map(_.typeSignature)
        case _ ⇒ Nil
      }
    }

    def topSort[T](gs: List[(T, List[T])]): List[T] = gs match {
      case (value, Nil) :: tail ⇒
        val diff = tail.diff(List(value)) map {
          case (fst, snd) ⇒ (fst, snd.diff(List(value)))
        }
        value :: topSort(diff)
      case node :: tail ⇒ topSort(tail :+ node)
      case _ => Nil
    }

    val configuration: List[Node] = {
      def matchConstructor(tree: c.Tree) = tree match {
        case q"injector.`package`.lazySingleton[${tpe: c.Type}]" ⇒
          (tpe, Singleton(_: Int, CT(tpe), _: List[CT], _: Option[CT], lzy = true))
        case q"injector.`package`.singleton[${tpe: c.Type}]" ⇒
          (tpe, Singleton(_: Int, CT(tpe), _: List[CT], _: Option[CT], lzy = false))
        case q"injector.`package`.prototype[${tpe: c.Type}]" ⇒
          (tpe, Prototype(_: Int, CT(tpe), _: List[CT], _: Option[CT]))
      }

      xs.toList.zipWithIndex map {
        case (q"injector.`package`.instance[${tpe: c.Type}](${expr: c.Tree})", n) ⇒
          Instance(n, CT(tpe), expr)
        case (tree @ q"${expr: c.Tree}.use[${impl: c.Type}]", n) ⇒
          val (tpe, f) = matchConstructor(expr)
          checkIsNotAbstract(impl, tree.pos)
          checkIsSuper(tpe, impl, tree.pos)
          f(n, extractDependencies(impl).map(CT), Some(CT(impl)))
        case (tree, n) ⇒
          val (tpe, f) = matchConstructor(tree)
          checkIsNotAbstract(tpe, tree.pos)
          f(n, extractDependencies(tpe).map(CT), None)
      }
    }

    val configMap = configuration.map(node ⇒ (node.ct, node)).toMap

    def nodeName(node: Node): TermName = TermName(s"dep${node.n.toString}")

    def ctName(ct: CT): TermName = nodeName(configMap(ct))

    val definitions = {
      val withDeps = configuration map {
        case x: NodeWithDependencies ⇒ (x.ct, x.dependencies)
        case x ⇒ (x.ct, Nil)
      }
      topSort(withDeps).map(configMap) map {
        case node: NodeWithDependencies ⇒
          def depsCode(tpe: c.Type) = {
            val arguments = node.dependencies.map(d ⇒ q"${ctName(d)}")
            val sizeOfConstructors = tpe.decls.toList flatMap {
              case m: MethodSymbol if m.isConstructor ⇒
                m.paramLists.map(_.length)
              case _ ⇒ Nil
            }
            @tailrec def rec(acc: List[List[c.Tree]], sizes: List[Int], args: List[c.Tree]): List[List[c.Tree]] = {
              sizes match {
                case Nil ⇒ acc
                case n :: ns ⇒
                  val (argsPart, tl) = args.splitAt(n)
                  rec(argsPart :: acc, ns, tl)
              }
            }
            rec(Nil, sizeOfConstructors, arguments).reverse
          }
          node match {
            case node @ Singleton(_, value, deps, impl, false) ⇒
              val constructor = impl.getOrElse(value).tpe
              q"val ${nodeName(node)} = new $constructor(...${depsCode(constructor)})"
            case node @ Singleton(_, value, deps, impl, true) ⇒
              val constructor = impl.getOrElse(value).tpe
              q"lazy val ${nodeName(node)} = new $constructor(...${depsCode(constructor)})"
            case node @ Prototype(_, value, deps, impl) ⇒
              val constructor = impl.getOrElse(value).tpe
              q"def ${nodeName(node)} = new $constructor(...${depsCode(constructor)})"
          }
        case node: Instance ⇒
          q"val ${nodeName(node)} = ${node.value}"
      }
    }

    def generateDotFile() = {
      val content = {
        def typeToShape(x: Node): String = x match {
          case _: Singleton ⇒ "box"
          case _: Prototype ⇒ "ellipse"
          case _: Instance ⇒ "component"
        }
        val defs = configuration.map(x ⇒ s"${x.ct.name} [shape=${typeToShape(x)}];")
        def arrows = configuration.
          collect { case x: NodeWithDependencies ⇒ x}.
          flatMap(node ⇒ node.dependencies.map(dep ⇒ s"${dep.name} -> ${node.ct.name};"))

        s"""digraph $configName {
           |  ${defs.mkString("\n  ")}
           |  ${arrows.mkString("\n  ")}
           |}
           |""".stripMargin
      }
      // Write is dot folder is exists
      val dotFolder = new File("dot")
      if (dotFolder.exists()) {
        val source = new File(dotFolder, configName + ".dot")
        new PrintWriter(source) {
          write(content)
          close()
        }
      }
    }

    generateDotFile()

    q"""
      new Injector {
        ..$definitions
        val accessors = Map[String, () ⇒ Any](..${
          configuration map { x ⇒
            val dep = nodeName(x)
            q"(${x.ct.fullName}, () => $dep)"
          }
        })
      }
     """
  }

}

object InjectorMacro {
  def resolve[T: c.WeakTypeTag](c: blackbox.Context): c.Tree = {
    val helper = new InjectorMacro[c.type](c)
    helper.resolve[T]
  }
  def configure(c: blackbox.Context)(name: c.Tree)(xs: c.Tree*): c.Tree = {
    val helper = new InjectorMacro[c.type](c)
    helper.configure(name)(xs)
  }
  def configureDefault(c: blackbox.Context)(xs: c.Tree*): c.Tree = {
    val helper = new InjectorMacro[c.type](c)
    helper.configureDefault(xs)
  }
}
