# Injector

Injector is a small dependency injection library for Scala and Scala.js.

* [Install](#install)
* [Configure](#configure)
* [Switch implementations](#switch-implementations)
* [External dependencies](#external-dependencies)
* [Generate `*.dot` files](#generate-dot-files)

Install
-----

Add Injector dependency to your project.

```scala
// For Scala.js
libraryDependencies += "com.github.fomkin" %%% "injector" % "0.1.0"

// For Scala.jvm
libraryDependencies += "com.github.fomkin" %% "injector" % "0.1.0"
```

Configure
---------

Define modules

```scala
class RobotRegistry {
  def deploy(robot: Robot): Unit = {
    println(s"Robot ${robot.uuid} deployed")
  }
}

class LeftLeg()
class RightLeg()
class Robot(registry: RobotRegistry, leftLeg: LeftLeg, rightLeg: RightLeg) {
  val uuid = UUID.randomUUID()
  println(s"New robot $uuid created")
  registry.deploy(this)
}
```

Configure robots factory
 
```scala
import injector._

val injector = configure("robots")(
  singleton[RobotsRegistry],
  prototype[LeftLeg],
  prototype[RightLeg],
  prototype[Robot]
)
```

Inject your robot

```scala
val newRobot = injector[Robot]
// New robot 40dc6398-4a28-4b92-8579-403681c9fa92 created
// Robot 40dc6398-4a28-4b92-8579-403681c9fa92 deployed 
```

Switch implementations
----------------------

We can switch implementation of singletons and prototypes depends on environment.

```scala
class ServerLeftLeg(launcher: ReactiveRocketJump) extends LeftLeg

val injector = configure("robots")(
  ...
  singleton[RobotsRegistry],
  prototype[LeftLeg].use[ServerLeftLeg],
  prototype[RightLeg],
  prototype[Robot]
)
```

In this case injector.Injector will invoke `ServerLeftLeg` constrictor.
 
External dependencies
---------------------

Sometime you need dependency created out of scope of injector.

```scala
val injector = configure("robots")(
  ...
  instance(system.executionContext)
)
```

Generate `*.dot` files
----------------------

Injector will automatically generate Dot files if `dot` directory 
exists in project root folder. 
You can convert it to image via [Graphviz](http://www.graphviz.org)
