/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.runtime.slotted.pipes

import org.neo4j.cypher.internal.macros.AssertMacros.checkOnlyWhenAssertionsAreEnabled
import org.neo4j.cypher.internal.runtime.ClosingIterator
import org.neo4j.cypher.internal.runtime.CypherRow
import org.neo4j.cypher.internal.runtime.LenientCreateRelationship
import org.neo4j.cypher.internal.runtime.ReadableRow
import org.neo4j.cypher.internal.runtime.interpreted.commands.expressions.Expression
import org.neo4j.cypher.internal.runtime.interpreted.pipes.BaseCreatePipe
import org.neo4j.cypher.internal.runtime.interpreted.pipes.LazyLabel
import org.neo4j.cypher.internal.runtime.interpreted.pipes.LazyType
import org.neo4j.cypher.internal.runtime.interpreted.pipes.Pipe
import org.neo4j.cypher.internal.runtime.interpreted.pipes.QueryState
import org.neo4j.cypher.internal.util.attribution.Id
import org.neo4j.exceptions.InternalException
import org.neo4j.kernel.api.StatementConstants.NO_SUCH_NODE
import org.neo4j.kernel.api.StatementConstants.NO_SUCH_RELATIONSHIP

import java.util.function.ToLongFunction

/**
 * Extends BaseCreatePipe with slotted methods to create nodes and relationships.
 */
abstract class EntityCreateSlottedPipe(source: Pipe) extends BaseCreatePipe(source) {

  /**
   * Create node and return id.
   */
  protected def createNode(context: CypherRow, state: QueryState, command: CreateNodeSlottedCommand): Long = {
    val labelIds = command.labels.map(_.getOrCreateId(state.query)).toArray
    val nodeId = state.query.createNodeId(labelIds)
    command.properties.foreach(setProperties(context, state, nodeId, _, state.query.nodeWriteOps))
    nodeId
  }

  /**
   * Create relationship and return id.
   */
  protected def createRelationship(
    context: CypherRow,
    state: QueryState,
    command: CreateRelationshipSlottedCommand
  ): Long = {

    def handleMissingNode(nodeName: String) =
      if (state.lenientCreateRelationship) NO_SUCH_RELATIONSHIP
      else throw new InternalException(LenientCreateRelationship.errorMsg(command.relName, nodeName))

    val startNodeId = command.startNodeIdGetter.applyAsLong(context)
    val endNodeId = command.endNodeIdGetter.applyAsLong(context)
    val typeId = command.relType.getOrCreateType(state.query)

    if (startNodeId == NO_SUCH_NODE) handleMissingNode(command.startName)
    else if (endNodeId == NO_SUCH_NODE) handleMissingNode(command.endName)
    else {
      val relationship = state.query.createRelationshipId(startNodeId, endNodeId, typeId)
      command.properties.foreach(setProperties(context, state, relationship, _, state.query.relationshipWriteOps))
      relationship
    }
  }
}

case class CreateNodeSlottedCommand(idOffset: Int, labels: Seq[LazyLabel], properties: Option[Expression]) {
  checkOnlyWhenAssertionsAreEnabled(labels.toSet.size == labels.size)
}

case class CreateRelationshipSlottedCommand(
  relIdOffset: Int,
  startNodeIdGetter: ToLongFunction[ReadableRow],
  relType: LazyType,
  endNodeIdGetter: ToLongFunction[ReadableRow],
  properties: Option[Expression],
  relName: String,
  startName: String,
  endName: String
) {

  val startVariableName: String = {
    // " UNNAMED X" -> Auto generated variable names, do not expose
    if (startName.startsWith(" "))
      ""
    else
      startName
  }

  val endVariableName: String = {
    // " UNNAMED X" -> Auto generated variable names, do not expose
    if (endName.startsWith(" "))
      ""
    else
      endName
  }
}

/**
 * Create nodes and relationships from slotted commands.
 */
case class CreateSlottedPipe(
  source: Pipe,
  nodes: IndexedSeq[CreateNodeSlottedCommand],
  relationships: IndexedSeq[CreateRelationshipSlottedCommand]
)(val id: Id = Id.INVALID_ID)
    extends EntityCreateSlottedPipe(source) {

  override protected def internalCreateResults(
    input: ClosingIterator[CypherRow],
    state: QueryState
  ): ClosingIterator[CypherRow] = {
    input.map {
      row =>
        var i = 0
        while (i < nodes.length) {
          val command = nodes(i)
          val nodeId = createNode(row, state, command)
          row.setLongAt(command.idOffset, nodeId)
          i += 1
        }

        i = 0
        while (i < relationships.length) {
          val command = relationships(i)
          val relationshipId = createRelationship(row, state, command)
          row.setLongAt(command.relIdOffset, relationshipId)
          i += 1
        }

        row
    }
  }
}
