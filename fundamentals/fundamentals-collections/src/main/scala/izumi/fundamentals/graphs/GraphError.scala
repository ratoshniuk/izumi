package izumi.fundamentals.graphs

import izumi.fundamentals.graphs.tools.MutationResolver.{Annotated, Axis, MutSel, Node}
import izumi.fundamentals.graphs.struct.IncidenceMatrix

sealed trait AbstractGraphError

sealed trait GraphError[N] extends AbstractGraphError

sealed trait DAGError[N] extends GraphError[N]

object DAGError {

  case class LoopBreakerFailed[N](loopMember: N) extends DAGError[N]

  case class UnexpectedLoops[N]() extends DAGError[N]

}

sealed trait GraphTraversalError[N] extends GraphError[N]

object GraphTraversalError {
  case class UnrecoverableLoops[N]() extends GraphTraversalError[N]
}

sealed trait ConflictResolutionError[N] extends GraphTraversalError[N]

object ConflictResolutionError {

  case class AmbigiousActivationsSet[N](issues: Map[String, Set[Axis]]) extends ConflictResolutionError[N]

  case class AmbigiousActivationDefs[N](node: Annotated[N], issues: Map[String, Set[Axis]]) extends ConflictResolutionError[N]

  case class AmbigiousDefinitions[N](ambigious: Map[N, Seq[Annotated[N]]]) extends ConflictResolutionError[N]

  case class ConflictingDefs[N, V](defs: Map[Annotated[N], Seq[Node[N, V]]]) extends ConflictResolutionError[N]

  case class UnsolvedConflicts[N](defs: Map[MutSel[N], Set[Annotated[N]]]) extends ConflictResolutionError[N]

}

sealed trait GCError[N] extends GraphTraversalError[N]

object GCError {

  case class MissingRoots[N](roots: Set[N]) extends GCError[N]

}

sealed trait ToposortError[T] extends GraphTraversalError[T]

object ToposortError {

  final case class UnexpectedLoop[T](done: Seq[T], matrix: IncidenceMatrix[T]) extends ToposortError[T]

  final case class InconsistentInput[T](issues: IncidenceMatrix[T]) extends ToposortError[T]

}
