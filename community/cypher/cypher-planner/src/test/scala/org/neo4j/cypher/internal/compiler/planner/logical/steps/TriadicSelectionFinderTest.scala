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
package org.neo4j.cypher.internal.compiler.planner.logical.steps

import org.neo4j.cypher.internal.compiler.planner.LogicalPlanningTestSupport
import org.neo4j.cypher.internal.compiler.planner.logical.LogicalPlanningContext
import org.neo4j.cypher.internal.compiler.planner.logical.QueryGraphProducer
import org.neo4j.cypher.internal.compiler.planner.logical.ordering.InterestingOrderConfig
import org.neo4j.cypher.internal.expressions.RelTypeName
import org.neo4j.cypher.internal.expressions.SemanticDirection
import org.neo4j.cypher.internal.expressions.SemanticDirection.INCOMING
import org.neo4j.cypher.internal.expressions.SemanticDirection.OUTGOING
import org.neo4j.cypher.internal.ir.QueryGraph
import org.neo4j.cypher.internal.ir.Selections.containsPatternPredicates
import org.neo4j.cypher.internal.logical.plans.Argument
import org.neo4j.cypher.internal.logical.plans.Expand
import org.neo4j.cypher.internal.logical.plans.ExpandAll
import org.neo4j.cypher.internal.logical.plans.IndexOrderNone
import org.neo4j.cypher.internal.logical.plans.LogicalPlan
import org.neo4j.cypher.internal.logical.plans.NodeByLabelScan
import org.neo4j.cypher.internal.logical.plans.Selection
import org.neo4j.cypher.internal.logical.plans.TriadicSelection
import org.neo4j.cypher.internal.planner.spi.PlanContext
import org.neo4j.cypher.internal.util.test_helpers.CypherFunSuite

class TriadicSelectionFinderTest extends CypherFunSuite with LogicalPlanningTestSupport with QueryGraphProducer {

  test("empty plan passes through") {
    val ctx = newMockedLogicalPlanningContextWithFakeAttributes(mock[PlanContext])
    val plan = newMockedLogicalPlan()

    testTriadic(plan, QueryGraph.empty, ctx) shouldBe empty
  }

  // Negative Predicate Expression

  test("MATCH (a:X)-->(b)-->(c) WHERE NOT (a)-->(c)") {
    val ctx = newMockedLogicalPlanningContextWithFakeAttributes(mock[PlanContext])
    val (plannerQuery, _) = producePlannerQueryForPattern("MATCH (a:X)-[r1]->(b)-[r2]->(c) WHERE NOT (a)-->(c)")
    val (expand1, selection) = produceTriadicTestCase()
    val triadic = produceTriadicTestPlan(expand1)

    testTriadic(selection, plannerQuery.lastQueryGraph, ctx) should contain only triadic
  }

  test("MATCH (a:X)-[:A]->(b)-[:B]->(c) WHERE NOT (a)-->(c) passes through") {
    val ctx = newMockedLogicalPlanningContextWithFakeAttributes(mock[PlanContext])
    val (plannerQuery, _) = producePlannerQueryForPattern("MATCH (a:X)-[:A]->(b)-[:B]->(c) WHERE NOT (a)-->(c)")
    val (_, selection) = produceTriadicTestCase(r1Types = Seq("A"), r2Types = Seq("B"))

    testTriadic(selection, plannerQuery.lastQueryGraph, ctx) shouldBe empty
  }

  test("MATCH (a:X)-[:A]->(b)-[:A]->(c) WHERE NOT (a)<-[:A]-(c) passes through") {
    val ctx = newMockedLogicalPlanningContextWithFakeAttributes(mock[PlanContext])
    val (plannerQuery, _) = producePlannerQueryForPattern("MATCH (a:X)-[:A]->(b)-[:A]->(c) WHERE NOT (a)<-[:A]-(c)")
    val (_, selection) = produceTriadicTestCase(r1Types = Seq("A"), r2Types = Seq("A"))

    testTriadic(selection, plannerQuery.lastQueryGraph, ctx) shouldBe empty
  }

  test("MATCH (a:X)-[:A]->(b)-[:A]->(c) WHERE NOT (a:X)-[:A]->(c)") {
    val ctx = newMockedLogicalPlanningContextWithFakeAttributes(mock[PlanContext])
    val (plannerQuery, _) = producePlannerQueryForPattern("MATCH (a:X)-[r1:A]->(b)-[r2:A]->(c) WHERE NOT (a)-[:A]->(c)")
    val (expand1, selection) = produceTriadicTestCase(r1Types = Seq("A"), r2Types = Seq("A"))
    val triadic = produceTriadicTestPlan(expand1, r2Types = Seq("A"))

    testTriadic(selection, plannerQuery.lastQueryGraph, ctx) should contain only triadic
  }

  test("MATCH (a:X)-[:A]->(b)-[:B]->(c) WHERE NOT (a:X)-[:A]->(c)") {
    val ctx = newMockedLogicalPlanningContextWithFakeAttributes(mock[PlanContext])
    val (plannerQuery, _) = producePlannerQueryForPattern("MATCH (a:X)-[r1:A]->(b)-[r2:B]->(c) WHERE NOT (a)-[:A]->(c)")
    val (expand1, selection) = produceTriadicTestCase(r1Types = Seq("A"), r2Types = Seq("B"))
    val triadic = produceTriadicTestPlan(expand1, r2Types = Seq("B"))

    testTriadic(selection, plannerQuery.lastQueryGraph, ctx) should contain only triadic
  }

  test("MATCH (a:X)-[:A]->(b)<-[:B]-(c) WHERE NOT (a:X)-[:A]->(c)") {
    val ctx = newMockedLogicalPlanningContextWithFakeAttributes(mock[PlanContext])
    val (plannerQuery, _) = producePlannerQueryForPattern("MATCH (a:X)-[r1:A]->(b)<-[r2:B]-(c) WHERE NOT (a)-[:A]->(c)")
    val (expand1, selection) = produceTriadicTestCase(r1Types = Seq("A"), r2Types = Seq("B"), r2Direction = INCOMING)
    val triadic = produceTriadicTestPlan(expand1, r2Types = Seq("B"), r2Direction = INCOMING)

    testTriadic(selection, plannerQuery.lastQueryGraph, ctx) should contain only triadic
  }

  // Positive Predicate Expression

  test("MATCH (a:X)-->(b)-[:A]->(c) WHERE (a:X)-[:A]->(c) passes through") {
    val ctx = newMockedLogicalPlanningContextWithFakeAttributes(mock[PlanContext])
    val (plannerQuery, _) = producePlannerQueryForPattern("MATCH (a:X)-[r1]->(b)-[r2:A]->(c) WHERE (a)-[:A]->(c)")
    val (_, selection) = produceTriadicTestCase(r2Types = Seq("A"))

    testTriadic(selection, plannerQuery.lastQueryGraph, ctx) shouldBe empty
  }

  test("MATCH (a:X)-->(b)-->(c) WHERE (a)-->(c)") {
    val ctx = newMockedLogicalPlanningContextWithFakeAttributes(mock[PlanContext])
    val (plannerQuery, _) = producePlannerQueryForPattern("MATCH (a:X)-[r1]->(b)-[r2]->(c) WHERE (a)-->(c)")
    val (expand1, selection) = produceTriadicTestCase()
    val triadic = produceTriadicTestPlan(expand1, predicateExpressionCase = true)

    testTriadic(selection, plannerQuery.lastQueryGraph, ctx) should contain only triadic
  }

  test("MATCH (a:X)-[:A]->(b)-[:B]->(c) WHERE (a)-->(c) passes through") {
    val ctx = newMockedLogicalPlanningContextWithFakeAttributes(mock[PlanContext])
    val (plannerQuery, _) = producePlannerQueryForPattern("MATCH (a:X)-[r1:A]->(b)-[r2:B]->(c) WHERE (a)-->(c)")
    val (_, selection) = produceTriadicTestCase(r1Types = Seq("A"), r2Types = Seq("B"))

    testTriadic(selection, plannerQuery.lastQueryGraph, ctx) shouldBe empty
  }

  test("MATCH (a:X)-[:A]->(b)-[:A]->(c) WHERE (a)<-[:A]-(c) passes through") {
    val ctx = newMockedLogicalPlanningContextWithFakeAttributes(mock[PlanContext])
    val (plannerQuery, _) = producePlannerQueryForPattern("MATCH (a:X)-[r1:A]->(b)-[r2:A]->(c) WHERE (a)<-[:A]-(c)")
    val (_, selection) = produceTriadicTestCase(r1Types = Seq("A"), r2Types = Seq("A"))

    testTriadic(selection, plannerQuery.lastQueryGraph, ctx) shouldBe empty
  }

  test("MATCH (a:X)-[:A]->(b)-[:A]->(c) WHERE (a:X)-[:A]->(c)") {
    val ctx = newMockedLogicalPlanningContextWithFakeAttributes(mock[PlanContext])
    val (plannerQuery, _) = producePlannerQueryForPattern("MATCH (a:X)-[r1:A]->(b)-[r2:A]->(c) WHERE (a)-[:A]->(c)")
    val (expand1, selection) = produceTriadicTestCase(r1Types = Seq("A"), r2Types = Seq("A"))
    val triadic = produceTriadicTestPlan(expand1, predicateExpressionCase = true, r2Types = Seq("A"))

    testTriadic(selection, plannerQuery.lastQueryGraph, ctx) should contain only triadic
  }

  test("MATCH (a:X)-[:A]->(b)-[:B]->(c) WHERE (a:X)-[:A]->(c)") {
    val ctx = newMockedLogicalPlanningContextWithFakeAttributes(mock[PlanContext])
    val (plannerQuery, _) = producePlannerQueryForPattern("MATCH (a:X)-[r1:A]->(b)-[r2:B]->(c) WHERE (a)-[:A]->(c)")
    val (expand1, selection) = produceTriadicTestCase(r1Types = Seq("A"), r2Types = Seq("B"))
    val triadic = produceTriadicTestPlan(expand1, predicateExpressionCase = true, r2Types = Seq("B"))

    testTriadic(selection, plannerQuery.lastQueryGraph, ctx) should contain only triadic
  }

  test("MATCH (a:X)-[:A]->(b)<-[:B]-(c) WHERE (a:X)-[:A]->(c)") {
    val ctx = newMockedLogicalPlanningContextWithFakeAttributes(mock[PlanContext])
    val (plannerQuery, _) = producePlannerQueryForPattern("MATCH (a:X)-[r1:A]->(b)<-[r2:B]-(c) WHERE (a)-[:A]->(c)")
    val (expand1, selection) = produceTriadicTestCase(r1Types = Seq("A"), r2Types = Seq("B"), r2Direction = INCOMING)
    val triadic = produceTriadicTestPlan(expand1, predicateExpressionCase = true, r2Types = Seq("B"), r2Direction = INCOMING)

    testTriadic(selection, plannerQuery.lastQueryGraph, ctx) should contain only triadic
  }

  // Negative Predicate Expression and matching labels

  test("MATCH (a:X)-->(b:Y)-->(c:Y) WHERE NOT (a)-->(c)") {
    val ctx = newMockedLogicalPlanningContextWithFakeAttributes(mock[PlanContext])
    val (plannerQuery, _) = producePlannerQueryForPattern("MATCH (a:X)-[r1]->(b:Y)-[r2]->(c:Y) WHERE NOT (a)-->(c)")
    val (expand1, selection) = produceTriadicTestCase(cLabels = Seq("Y"))
    val triadic = produceTriadicTestPlan(expand1, cLabels = Seq("Y"))

    testTriadic(selection, plannerQuery.lastQueryGraph, ctx) should contain only triadic
  }

  test("MATCH (a:X)-->(b:Y)-->(c:Z) WHERE NOT (a)-->(c) passes through") {
    val ctx = newMockedLogicalPlanningContextWithFakeAttributes(mock[PlanContext])
    val (plannerQuery, _) = producePlannerQueryForPattern("MATCH (a:X)-[r1]->(b:Y)-[r2]->(c:Z) WHERE NOT (a)-->(c)")
    val (_, selection) = produceTriadicTestCase(cLabels = Seq("Z"))

    testTriadic(selection, plannerQuery.lastQueryGraph, ctx) shouldBe empty
  }

  test("MATCH (a:X)-->(b:Y)-->(c) WHERE NOT (a)-->(c) passes through") {
    val ctx = newMockedLogicalPlanningContextWithFakeAttributes(mock[PlanContext])
    val (plannerQuery, _) = producePlannerQueryForPattern("MATCH (a:X)-[r1]->(b:Y)-[r2]->(c) WHERE NOT (a)-->(c)")
    val (_, selection) = produceTriadicTestCase()

    testTriadic(selection, plannerQuery.lastQueryGraph, ctx) shouldBe empty
  }

  test("MATCH (a:X)-->(b)-->(c:Z) WHERE NOT (a)-->(c)") {
    val ctx = newMockedLogicalPlanningContextWithFakeAttributes(mock[PlanContext])
    val (plannerQuery, _) = producePlannerQueryForPattern("MATCH (a:X)-[r1]->(b)-[r2]->(c:Z) WHERE NOT (a)-->(c)")
    val (expand1, selection) = produceTriadicTestCase(cLabels = Seq("Z"))
    val triadic = produceTriadicTestPlan(expand1, cLabels = Seq("Z"))

    testTriadic(selection, plannerQuery.lastQueryGraph, ctx) should contain only triadic
  }

  test("MATCH (a:X)-->(b:Y:Z)-->(c:Z) WHERE NOT (a)-->(c)") {
    val ctx = newMockedLogicalPlanningContextWithFakeAttributes(mock[PlanContext])
    val (plannerQuery, _) = producePlannerQueryForPattern("MATCH (a:X)-[r1]->(b:Y:Z)-[r2]->(c:Z) WHERE NOT (a)-->(c)")
    val (expand1, selection) = produceTriadicTestCase(cLabels = Seq("Z"))
    val triadic = produceTriadicTestPlan(expand1, cLabels = Seq("Z"))

    testTriadic(selection, plannerQuery.lastQueryGraph, ctx) should contain only triadic
  }

  // Positive Predicate Expression and matching labels

  test("MATCH (a:X)-->(b:Y)-->(c:Y) WHERE (a)-->(c)") {
    val ctx = newMockedLogicalPlanningContextWithFakeAttributes(mock[PlanContext])
    val (plannerQuery, _) = producePlannerQueryForPattern("MATCH (a:X)-[r1]->(b:Y)-[r2]->(c:Y) WHERE (a)-->(c)")
    val (expand1, selection) = produceTriadicTestCase(cLabels = Seq("Y"))
    val triadic = produceTriadicTestPlan(expand1, predicateExpressionCase = true, cLabels = Seq("Y"))

    testTriadic(selection, plannerQuery.lastQueryGraph, ctx) should contain only triadic
  }

  test("MATCH (a:X)-->(b:Y)-->(c:Z) WHERE (a)-->(c) passes through") {
    val ctx = newMockedLogicalPlanningContextWithFakeAttributes(mock[PlanContext])
    val (plannerQuery, _) = producePlannerQueryForPattern("MATCH (a:X)-[r1]->(b:Y)-[r2]->(c:Z) WHERE (a)-->(c)")
    val (_, selection) = produceTriadicTestCase(cLabels = Seq("Z"))

    testTriadic(selection, plannerQuery.lastQueryGraph, ctx) shouldBe empty
  }

  test("MATCH (a:X)-->(b:Y)-->(c) WHERE (a)-->(c) passes through") {
    val ctx = newMockedLogicalPlanningContextWithFakeAttributes(mock[PlanContext])
    val (plannerQuery, _) = producePlannerQueryForPattern("MATCH (a:X)-[r1]->(b:Y)-[r2]->(c) WHERE (a)-->(c)")
    val (_, selection) = produceTriadicTestCase()

    testTriadic(selection, plannerQuery.lastQueryGraph, ctx) shouldBe empty
  }

  test("MATCH (a:X)-->(b)-->(c:Z) WHERE (a)-->(c) passes through") {
    val ctx = newMockedLogicalPlanningContextWithFakeAttributes(mock[PlanContext])
    val (plannerQuery, _) = producePlannerQueryForPattern("MATCH (a:X)-[r1]->(b)-[r2]->(c:Z) WHERE (a)-->(c)")
    val (_, selection) = produceTriadicTestCase(cLabels = Seq("Z"))

    testTriadic(selection, plannerQuery.lastQueryGraph, ctx) shouldBe empty
  }

  test("MATCH (a:X)-->(b:Y:Z)-->(c:Z) WHERE (a)-->(c) passes through") {
    val ctx = newMockedLogicalPlanningContextWithFakeAttributes(mock[PlanContext])
    val (plannerQuery, _) = producePlannerQueryForPattern("MATCH (a:X)-[r1]->(b:Y:Z)-[r2]->(c:Z) WHERE (a)-->(c)")
    val (_, selection) = produceTriadicTestCase(cLabels = Seq("Z"))

    testTriadic(selection, plannerQuery.lastQueryGraph, ctx) shouldBe empty
  }

  // used to build plans that are passed to the triadicSelectionFinder. We need proper solveds thus
  private def produceTriadicTestCase(aLabel: String = "X",
                                     r1Types: Seq[String] = Seq.empty,
                                     r2Types: Seq[String] = Seq.empty,
                                     r2Direction: SemanticDirection = OUTGOING,
                                     cLabels: Seq[String] = Seq.empty): (Expand, Selection) = {
    val lblScan = NodeByLabelScan("a", labelName(aLabel), Set.empty, IndexOrderNone)
    val expand1 = Expand(lblScan, "a", OUTGOING, r1Types.map(RelTypeName(_)(pos)), "b", "r1", ExpandAll)
    val expand2 = Expand(expand1, "b", r2Direction, r2Types.map(RelTypeName(_)(pos)), "c", "r2", ExpandAll)
    val relationshipUniqueness = not(equals(varFor("r1"), varFor("r2")))
    val labelPredicates = cLabels.map(lbl => hasLabels("c", lbl))
    val selection = Selection(labelPredicates :+ relationshipUniqueness, expand2)
    (expand1, selection)
  }

  // used to build plan to assert an. Attributes can be ignored thus
  private def produceTriadicTestPlan(expand1: Expand,
                                     predicateExpressionCase: Boolean = false,
                                     r2Types: Seq[String] = Seq.empty,
                                     r2Direction: SemanticDirection = OUTGOING,
                                     cLabels: Seq[String] = Seq.empty): TriadicSelection = {
    val argument = Argument(Set("b", "r1"))
    val expand2B = Expand(argument, "b", r2Direction, r2Types.map(RelTypeName(_)(pos)), "c", "r2", ExpandAll)
    val relationshipUniqueness = not(equals(varFor("r1"), varFor("r2")))
    val labelPredicates = cLabels.map(lbl => hasLabels("c", lbl))
    val selectionB = Selection(labelPredicates :+ relationshipUniqueness, expand2B)
    TriadicSelection(expand1, selectionB, predicateExpressionCase, "a", "b", "c")
  }

  private def testTriadic(in: LogicalPlan, qg: QueryGraph, context: LogicalPlanningContext): Seq[LogicalPlan] = {
    val unsolvedPredicates = qg.selections.flatPredicatesSet
    val candidates = triadicSelectionFinder(in, unsolvedPredicates, qg, InterestingOrderConfig.empty, context).toSeq
    candidates.foreach {
      case SelectionCandidate(_, solvedPredicates) =>
        // All pattern predicates should be solved. This assumes that only triadic pattern predicates are used in the tests.
        solvedPredicates should equal(unsolvedPredicates.filter(containsPatternPredicates))
    }
    candidates.map(_.plan)
  }
}
