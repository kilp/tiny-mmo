package com.github.mumoshu.mmo.models.world.world

import com.github.mumoshu.mmo.models.{WorldChangeHandler, Terrain}
import com.github.mumoshu.mmo.thrift.message.Presentation

sealed trait Identity {
  /**
   * The representation of this identity in string
   * @return
   */
  def str: String
}

case class StringIdentity(id: String) extends Identity {
  def str = id
}

case class Position(x: Float, z: Float) {
  def move(m: Movement) = copy(x = x + m.diff.x, z = z + m.diff.z)
  def distance(other: Position) = math.sqrt(math.pow(x - other.x, 2) + math.pow(z - other.z, 2))
  val distanceFromOrigin = math.sqrt(math.pow(x, 2) + math.pow(z, 2))
  def lerp(to: Position, step: Float): Position = {
    val distanceToDestX = to.x - x
    val distanceToDestZ = to.z - z
    val distance = Position(distanceToDestX, distanceToDestZ).distanceFromOrigin.toFloat
    val a = step / distance
    if (distance > step) {
      copy(x = a * distanceToDestX, z = a * distanceToDestZ)
    } else {
      copy(x = distanceToDestX, z = distanceToDestZ)
    }
  }
}

case class Movement(diff: Position)

/**
 * 動物または静物といった「物」
 */
sealed trait Thing {
  val id: Identity

  /**
   * 移動先に何もなければ移動する
   * @param movement
   * @param world
   * @return
   */
  def tryMove(movement: Movement)(implicit world: World): Thing = {
    val simulated = move(movement)
    if (world.existsAt(simulated.position))
      this
    else
      simulated
  }

  def tryMoveTo(p: Position)(implicit world: World): Thing = {
    if (world.existsAt(p))
      this
    else
      moveTo(p)
  }

  /**
   * 現在位置
   * @return
   */
  def position: Position

  /**
   * 移動先に何があったとしても強制的に移動する
   * @param movement
   * @return
   */
  def move(movement: Movement): Thing

  def moveTo(p: Position): Thing

}

case class StaticObject(id: Identity, prefab: String, position: Position) extends Thing {
  /**
   * 移動先に何があったとしても強制的に移動する
   * @param movement
   * @return
   */
  def move(movement: Movement) = copy(position = position.move(movement))

  def moveTo(p: Position) = copy(position = p)
}

/**
 * エネミーやプレイヤーなどの生物
 */
sealed trait Living extends Thing {

  val life: Float

}

/**
 * LivingThingのDeadBody
 */
sealed trait Died extends Thing
sealed trait Player
sealed trait Attacker extends Living
sealed trait Target extends Living {
  /**
   * ダメージをうける
   * @return
   */
  def reduceLife(reducedLife: Float): Either[Living, Died]
}

// プレイヤーは何もしていないか
case class LivingPlayer(id: Identity, life: Float, position: Position) extends Living with Player with Attacker with Target {
  /**
   * 移動先に何があったとしても強制的に移動する
   * @param movement
   * @return
   */
  def move(movement: Movement) = copy(position = position.move(movement))

  def moveTo(p: Position) = copy(position = p)

  def reduceLife(reducedLife: Float) = {
    val lifeAfter: Float = life - reducedLife
    if (lifeAfter > 0)
      Left(copy(life = lifeAfter))
    else
      Right(DiedPlayer(id = id, position = position))
  }
}
//// 攻撃しているか
//case class AttackingPlayer(id: Identity, life: Float, position: Position) extends Living with Player with Attacker{
//  /**
//   * 移動先に何があったとしても強制的に移動する
//   * @param movement
//   * @return
//   */
//  def move(movement: Movement) = copy(position = position.move(movement))
//}
//// 攻撃を受けているか
//case class TargetPlayer(id: Identity, life: Float, position: Position) extends Living with Player with Target{
//  /**
//   * 移動先に何があったとしても強制的に移動する
//   * @param movement
//   * @return
//   */
//  def move(movement: Movement) = copy(position = position.move(movement))
//
//  /**
//   * ダメージをうける
//   * @return
//   */
//  def reduceLife(reducedLife: Float) = {
//    val lifeAfter: Float = life - reducedLife
//    if (lifeAfter > 0)
//      Left(copy(life = lifeAfter))
//    else
//      Right(DiedPlayer(id = id, position = position))
//  }
//}
// 死んでいる
case class DiedPlayer(id: Identity, position: Position) extends Player with Died{
  /**
   * 移動先に何があったとしても強制的に移動する
   * @param movement
   * @return
   */
  def move(movement: Movement) = copy(position = position.move(movement))
  def moveTo(p: Position) = copy(position = p)
}

trait World {

  def say(p: Thing, text: String): World

  def existsAt(position: Position): Boolean

  def appear(t: Thing): World

  def disappear(t: Thing): World

  def attack(attacker: Attacker, target: Target): (World, Attacker, Thing)

  def findExcept(id: Identity): List[Thing]

  def find(id: Identity): Option[Thing]

  def tryMove(livingThing: Thing, movement: Movement): (World, Thing)

  def tryMoveTo(t: Thing, p: Position): (World, Thing)

  def join(p: LivingPlayer): World

  def leave(p: LivingPlayer): World

  def tryMove(id: Identity, p: Position): (World, Thing)

  def tryAttack(attackerId: Identity, targetId: Identity): (World, Thing, Thing)

  def trySay(id: Identity, what: String): World

  def tryShout(id: Identity, what: String): World
}

trait ConnectedWorld extends World {

  abstract override def attack(attacker: Attacker, target: Target): (World, Attacker, Thing) = {
    // TODO Send this event to nearby players
    super.attack(attacker, target)
  }

}

sealed trait Speech {
  /**
   * Who
   */
  val id: Identity
  val text: String
}

case class Say(id: Identity, text: String) extends Speech
case class Shout(id: Identity, text: String) extends Speech

case class InMemoryWorld(val things: List[Thing], val terrain: Terrain, val speeches: List[Speech], changeHandler: WorldChangeHandler) extends World {
  def createPresentation(p: Presentation): InMemoryWorld =
    copy(changeHandler = changeHandler.presentationCreated(p))

  def findAllThings(identity: Identity): InMemoryWorld = copy(changeHandler = changeHandler.tellThings(identity, things))

  def getPosition(identity: Identity, targetId: Identity): InMemoryWorld =
    copy(changeHandler = changeHandler.toldPosition(identity, targetId, things.find(_.id == targetId).get.position))

  def myId(identity: Identity): InMemoryWorld = copy(changeHandler = changeHandler.toldOwnId(identity))


  def replayChanges(): InMemoryWorld = copy(changeHandler = changeHandler.handleAllChanges())

  def say(p: Thing, text: String) =
    copy(speeches = speeches :+ Say(p.id, text), changeHandler = changeHandler.said(p.id, text))

  def appear(t: Thing) =
    copy(things = things :+ t)

  def disappear(t: Thing) =
    copy(things = things.filter(_ != t))

  def attack(attacker: Attacker, target: Target) = {
    // TODO atk - def
    val dealtDmg = 1
    val thingsAfter = things.map {
      case t if t == target =>
        target.reduceLife(dealtDmg).merge
      case t =>
        t
    }
    val t2 = thingsAfter.find(_.id == target.id).getOrElse {
      throw new RuntimeException("Target not found.")
    }
    (
      copy(things = thingsAfter, changeHandler = changeHandler.attacked(attacker.id, target.id)),
      attacker,
      t2
    )
  }

  def find(id: Identity) = things.find(_.id == id)

  def findExcept(id: Identity) = things.filterNot(_.id == id)

  def join(p: LivingPlayer) = copy(things = things :+ p, changeHandler = changeHandler.joined(p.id, "someone"))

  def leave(p: LivingPlayer) = copy(things = things.filter(_.id != p.id), changeHandler = changeHandler.left(p.id))

  def tryMove(thing: Thing, movement: Movement) = (
    copy(
      things = things.map {
        case t if t.id == thing.id =>
          t.tryMove(movement)(this)
        case t =>
          t
      }
    ),
    thing
  )

  def existsAt(position: Position) = things.exists(t =>
    Math.sqrt(Math.pow(position.x - t.position.x, 2) + Math.pow(position.z - t.position.z, 2)) < 1.0f
  )

  def tryMoveTo(t: Thing, p: Position) = {
    val moved = t.tryMoveTo(p)(this)
    (
      copy(
        things = things.map {
          case tt if tt.id == t.id =>
            moved
          case tt =>
            tt
        },
        changeHandler = changeHandler.movedTo(t.id, p)
      ),
      moved
    )
  }

  def tryAttack(attackerId: Identity, targetId: Identity) = {
    // TODO atk - def
    val dealtDmg = 1
    val thingsAfter = things.map {
      case t if t.id == targetId =>
        t.asInstanceOf[LivingPlayer].reduceLife(dealtDmg).merge
      case t =>
        t
    }
    val attacker = thingsAfter.find(_.id == attackerId).getOrElse {
      throw new RuntimeException("Attacker not found")
    }
    val t2 = thingsAfter.find(_.id == targetId).getOrElse {
      throw new RuntimeException("Target not found.")
    }
    (
      copy(things = thingsAfter, changeHandler = changeHandler.attacked(attackerId, targetId)),
      attacker,
      t2
      )
  }

  def tryMove(id: Identity, p: Position): (World, Thing) = {
    val moved = things.filter(_.id == id).head.tryMoveTo(p)(this)
    (
      copy(
        things = things.map {
          case tt if tt.id == id =>
            moved
          case tt =>
            tt
        },
        changeHandler = changeHandler.movedTo(id, p)
      ),
      moved
      )
  }

  def trySay(id: Identity, what: String) = {
    copy(speeches = speeches :+ Say(id, what), changeHandler = changeHandler.said(id, what))
  }

  def tryShout(id: Identity, what: String) = {
    copy(speeches = speeches :+ Shout(id, what), changeHandler = changeHandler.shout(id, what))
  }
}
