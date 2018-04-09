/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.stream.{ Attributes, Inlet, Outlet, UniformFanInShape }
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }

import scala.collection.{ immutable, mutable }
import scala.reflect.ClassTag
import scala.language.higherKinds

/**
 * MergeLatest joins elements from N input streams into stream of lists of size N.
 * i-th element in list is the latest emitted element from i-th input stream.
 * MergeLatest emits list for each element emitted from some input stream,
 * but only after each stream emitted at least one element
 *
 * '''Emits when''' element is available from some input and each input emits at least one element from stream start
 *
 * '''Completes when''' all upstreams complete (eagerClose=false) or one upstream completes (eagerClose=true)
 *
 * '''Cancels when''' downstream cancels
 *
 */
object MergeLatest {
  /**
   * Create a new `MergeLatest` with the specified number of input ports.
   *
   * @param inputPorts number of input ports
   * @param eagerComplete if true, the merge latest will complete as soon as one of its inputs completes.
   */
  def apply[T: ClassTag](inputPorts: Int, eagerComplete: Boolean = false): GraphStage[UniformFanInShape[T, List[T]]] =
    new MergeLatest[T, List[T]](inputPorts, eagerComplete)(_.toList)

}

final class MergeLatest[T: ClassTag, M](val inputPorts: Int, val eagerClose: Boolean)(buildElem: Array[T] ⇒ M) extends GraphStage[UniformFanInShape[T, M]] {
  require(inputPorts >= 1, "input ports must be >= 1")

  val in: immutable.IndexedSeq[Inlet[T]] = Vector.tabulate(inputPorts)(i ⇒ Inlet[T]("MergeLatest.in" + i))
  val out: Outlet[M] = Outlet[M]("MergeLatest.out")
  override val shape: UniformFanInShape[T, M] = UniformFanInShape(out, in: _*)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with OutHandler {
    private val activeStreams: java.util.HashSet[Int] = new java.util.HashSet[Int]()
    private var runningUpstreams: Int = inputPorts
    private def upstreamsClosed: Boolean = runningUpstreams == 0
    private def allMessagesReady: Boolean = activeStreams.size == inputPorts
    private val messages: Array[T] = new Array[T](inputPorts)

    override def preStart(): Unit = in.foreach(tryPull)

    in.zipWithIndex.foreach {
      case (input, index) ⇒
        setHandler(input, new InHandler {
          override def onPush(): Unit = {
            messages.update(index, grab(input))
            activeStreams.add(index)
            if (allMessagesReady) emit(out, buildElem(messages))
            tryPull(input)
          }

          override def onUpstreamFinish(): Unit = {
            if (!eagerClose) {
              runningUpstreams -= 1
              if (upstreamsClosed) completeStage()
            } else completeStage()
          }
        })
    }

    override def onPull(): Unit = {
      var i = 0
      while (i < inputPorts) {
        if (!hasBeenPulled(in(i))) tryPull(in(i))
        i += 1
      }
    }

    setHandler(out, this)
  }

  override def toString = "MergeLatest"
}
