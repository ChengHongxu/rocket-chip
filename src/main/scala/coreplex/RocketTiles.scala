// See LICENSE.SiFive for license details.

package coreplex

import Chisel._
import config._
import diplomacy._
import rocket._
import tile._
import uncore.tilelink2._

sealed trait ClockCrossing
case object Synchronous extends ClockCrossing
case object Rational extends ClockCrossing
case class Asynchronous(depth: Int, sync: Int = 2) extends ClockCrossing

case object RocketTilesKey extends Field[Seq[RocketTileParams]]
case object RocketCrossing extends Field[ClockCrossing]

trait HasRocketTiles extends CoreplexRISCVPlatform {
  val module: HasRocketTilesModule

  private val crossing = p(RocketCrossing)
  private val configs = p(RocketTilesKey)

  // TODO: hack to fix deduplication; see PR https://github.com/ucb-bar/berkeley-hardfloat/pull/14
  hardfloat.consts
  
  // Modified rocketWires to get access to tile <ChengHongxu>
  val rocketWires: Seq[(HasRocketTilesBundle => Unit, Option[RocketTile])] = configs.zipWithIndex.map { case (c, i) =>
    val pWithExtra = p.alterPartial {
      case TileKey => c
      case BuildRoCC => c.rocc
      case SharedMemoryTLEdge => l1tol2.node.edgesIn(0)
      case PAddrBits => l1tol2.node.edgesIn(0).bundle.addressBits
    }

    // Hack debug interrupt into a node (future debug module should use diplomacy)
    val debugNode = IntInternalInputNode(IntSourcePortSimple())

    val intBar = LazyModule(new IntXbar)
    intBar.intnode := debugNode
    intBar.intnode := clint.intnode // msip+mtip
    intBar.intnode := plic.intnode // meip
    if (c.core.useVM) intBar.intnode := plic.intnode // seip

    crossing match {
      case Synchronous => {
        val tile = LazyModule(new RocketTile(c, i)(pWithExtra))
        val buffer = LazyModule(new TLBuffer)
        buffer.node :=* tile.masterNode
        l1tol2.node :=* buffer.node
        tile.slaveNode :*= cbus.node
        tile.intNode := intBar.intnode
        ((io: HasRocketTilesBundle) => {
          // leave clock as default (simpler for hierarchical PnR)
          tile.module.io.hartid := UInt(i)
          tile.module.io.resetVector := io.resetVector
          debugNode.bundleOut(0)(0) := debug.module.io.debugInterrupts(i)
        },
        Some(tile))
      }
      case Asynchronous(depth, sync) => {
        val wrapper = LazyModule(new AsyncRocketTile(c, i)(pWithExtra))
        val sink = LazyModule(new TLAsyncCrossingSink(depth, sync))
        val source = LazyModule(new TLAsyncCrossingSource(sync))
        sink.node :=* wrapper.masterNode
        l1tol2.node :=* sink.node
        wrapper.slaveNode :*= source.node
        wrapper.intNode := intBar.intnode
        source.node :*= cbus.node
        ((io: HasRocketTilesBundle) => {
          wrapper.module.clock := io.tcrs(i).clock
          wrapper.module.reset := io.tcrs(i).reset
          wrapper.module.io.hartid := UInt(i)
          wrapper.module.io.resetVector := io.resetVector
          debugNode.bundleOut(0)(0) := debug.module.io.debugInterrupts(i)
        },
        None)
      }
      case Rational => {
        val wrapper = LazyModule(new RationalRocketTile(c, i)(pWithExtra))
        val sink = LazyModule(new TLRationalCrossingSink(util.FastToSlow))
        val source = LazyModule(new TLRationalCrossingSource)
        sink.node :=* wrapper.masterNode
        l1tol2.node :=* sink.node
        wrapper.slaveNode :*= source.node
        wrapper.intNode := intBar.intnode
        source.node :*= cbus.node
        ((io: HasRocketTilesBundle) => {
          wrapper.module.clock := io.tcrs(i).clock
          wrapper.module.reset := io.tcrs(i).reset
          wrapper.module.io.hartid := UInt(i)
          wrapper.module.io.resetVector := io.resetVector
          debugNode.bundleOut(0)(0) := debug.module.io.debugInterrupts(i)
        },
        None)
      }
    }
  }
}

trait HasRocketTilesBundle extends CoreplexRISCVPlatformBundle {
  val outer: HasRocketTiles
  val tcrs = Vec(p(RocketTilesKey).size, new Bundle {
    val clock = Clock(INPUT)
    val reset = Bool(INPUT)
  })

  // Add ipc port to expose instret and cycle
  val ipc = new Bundle{
    val instr = UInt(OUTPUT, 64.W)
    val cycle = UInt(OUTPUT, 64.W)
  }
}

trait HasRocketTilesModule extends CoreplexRISCVPlatformModule {
  val outer: HasRocketTiles
  val io: HasRocketTilesBundle
  outer.rocketWires.foreach { _._1(io) }

  val rocketTile = outer.rocketWires(0)._2.get
  require(rocketTile != null, s"rocketTile is empty, there is no Synchronous Tile in the SoC")

  // connect cycle and instret of CSRFile to IPCPort <ChengHongxu>
  io.ipc.instr := rocketTile.module.io.ipc.instr
  io.ipc.cycle := rocketTile.module.io.ipc.cycle
}
